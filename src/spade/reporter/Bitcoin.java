/*
 --------------------------------------------------------------------------------
 SPADE - Support for Provenance Auditing in Distributed Environments.
 Copyright (C) 2015 SRI International

 This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU General Public License as
 published by the Free Software Foundation, either version 3 of the
 License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program. If not, see <http://www.gnu.org/licenses/>.
 --------------------------------------------------------------------------------
 */
 package spade.reporter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Arrays;
import javax.xml.bind.DatatypeConverter;
import java.util.Calendar;
import java.util.Date;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.OutputStreamWriter;
import java.io.Writer;
import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;

import spade.core.AbstractReporter;
import spade.core.AbstractVertex;
import spade.core.Graph;
import spade.reporter.pdu.Pdu;
import spade.reporter.pdu.PduParser;
import spade.vertex.prov.Activity;
import spade.vertex.prov.Entity;
import spade.vertex.prov.Agent;
import spade.edge.prov.WasInformedBy;
import spade.edge.prov.Used;
import spade.edge.prov.WasGeneratedBy;
import spade.edge.prov.WasAttributedTo;

import spade.utility.bitcoin.Vin;
import spade.utility.bitcoin.Vout;
import spade.utility.bitcoin.Transaction;
import spade.utility.bitcoin.Block;
import spade.utility.bitcoin.RPCManager;


/**
 * Bitcoin reporter for SPADE
 *
 * @author Hasanat Kazmi
 */
public class Bitcoin extends AbstractReporter { 
    
    // pause_time is the time interval thread sleeps before quering the server for new blocks
    private final int pause_time = 10000;
    
    // file used to save block index, i such that block 0 to i have been processed
    private String progress_file = "/tmp/bitcoin.reporter.progress";

    private RPCManager block_reader;
    
    // for rate monitoring
    private int total_blocks_processed = 0;
    private int total_tx_processed = 0;
    private int recent_blocks_processed = 0;
    private int recent_tx_processed = 0;
    private Date date;
    
    //
    private boolean shutdown=false;

    // ref to last block processed 
    private Activity last_block_node;
    
            
    @Override
    public boolean launch(final String arguments) {
        /*
        * You can specify both starting and ending block
        * start=<starting block index> end=<ending block index>
        * or just the starting block (and ending block will be whatever the blockchain returns)
        * start=<starting block index>
        * or just the ending block (and starting block will be genesis block)
        * end=<ending block index>
        * if no argument is given, system will pick from the last block that was processed in last run
        */
        Runnable eventThread = new Runnable() {
            public void run() {
                int start_block=-1;
                int end_block=-1;
                try {
                    String[] pairs = arguments.split("\\s+");
                    for (String pair : pairs) {
                        String[] keyvalue = pair.split("=");
                        String key = keyvalue[0];
                        int value = Integer.parseInt(keyvalue[1]);

                        if (key.equals("start")) {
                            start_block = value;
                        }
                        if (key.equals("end")) {
                            end_block = value;
                        }
                    }
                } catch (NullPointerException e) {

                }
                runner(start_block, end_block);
            }
        };
        new Thread(eventThread, "BitcoinReporter-Thread").start();
        return true;
    }
    
    @Override
    public boolean shutdown() {
        shutdown=true;
        // TODO: wait for the main loop to break and then return true
        return true;
    }
    
    int getLastBlockProcessedFromCache() throws Exception{
        int last_block = -1;
        try {
            new File(progress_file).createNewFile(); // only creates if one doesn't exist
            String contents = new String(Files.readAllBytes(Paths.get(progress_file)));
            contents = contents.replace("\n", "").replace("\r", "");
            if (contents.equals("")) { 
                last_block = -1;
            }
            else {
                last_block = Integer.parseInt(contents);
            }
        } catch (IOException e) {
            Bitcoin.log(Level.SEVERE, "Couldn't open progress file or progress file has unexpected content. Path: " + progress_file, e);
            throw e;
        }
        return last_block;
    }
    
    void writeBlockProgressToCache(int index) throws Exception{
        try {
            new File(progress_file).createNewFile(); // only creates if one doesn't exist
            Writer wr = new FileWriter(progress_file);
            wr.write(String.valueOf(index));
            wr.close();
        } catch (IOException e) {
            Bitcoin.log(Level.SEVERE, "Couldn't open progress file or write to it. Path: " + progress_file, e);
            throw e;
        }       
    }
    
    void reportProgress(final Block block) {
        recent_blocks_processed++;
        recent_tx_processed += block.getTransactions().size();
        total_blocks_processed++;
        total_tx_processed += block.getTransactions().size();
        long diff = Calendar.getInstance().getTime().getTime() - date.getTime();
        if (diff > 60000) {
            Bitcoin.log(Level.INFO, "Rate: " + (int) recent_blocks_processed/(diff/60000) +" blocks/min. Total in the session: " + total_blocks_processed, null);
            Bitcoin.log(Level.INFO, "Rate: " + (int) recent_tx_processed/(diff/60000) +" txes/min. Total in the session: " + total_tx_processed, null);
            
            date = Calendar.getInstance().getTime();
            recent_blocks_processed = 0;
            recent_tx_processed = 0;
        }
    }
    
    void reportBlock(final Block block) {
        
        // block
        Activity block_node = new Activity();
        block_node.addAnnotations(new HashMap<String, String>(){
            {
                put("blockHash", block.getHash()); 
                put("blockHeight", Integer.toString(block.getHeight()));
                put("blockConfirmations", Integer.toString(block.getConfirmations()));
                put("blockTime", Integer.toString(block.getTime())); 
                put("blockDifficulty", Integer.toString(block.getDifficulty())); 
                put("blockChainwork", block.getChainwork());
            }
        });
        putVertex(block_node);
                
        for(final Transaction tx: block.getTransactions()) {
            // Tx
            Activity tx_node = new Activity();
            tx_node.addAnnotations(new HashMap<String, String>(){
                {
                    put("transactionHash", tx.getId());
                }
            });
            if (tx.getLocktime() != 0) {
                tx_node.addAnnotation("transactionLoctime", Integer.toString(tx.getLocktime()));
            }
            if (tx.getCoinbaseValue() != null) {
                tx_node.addAnnotation("coinbase", tx.getCoinbaseValue());
            }

            putVertex(tx_node);
            
            // Tx edge
            WasInformedBy tx_edge = new WasInformedBy(tx_node, block_node);
            putEdge(tx_edge);
            
            for (final Vin vin: tx.getVins()) {
                // Vin
                if (vin.isCoinbase() == false) {
                    Entity vin_vertex = new Entity();
                    vin_vertex.addAnnotations(new HashMap<String, String>(){
                        {
                            put("transactionHash", vin.getTxid());
                            put("transactionIndex", Integer.toString(vin.getN()));
                        }
                    });
                    // Vin nodes are already present (except few cases)
                    // confirm that system pulls these vertexes from the db
                    putVertex(vin_vertex); 
                    
                    // Vin Edge
                    Used vin_edge = new Used(tx_node,vin_vertex) ;
                    putEdge(vin_edge);
                } 
            }

            for (final Vout vout: tx.getVouts()) {
                // Vout Vertex
                Entity vout_vertex = new Entity();
                vout_vertex.addAnnotations(new HashMap<String, String>(){
                    {
                        put("transactionHash", tx.getId());
                        put("transactionIndex", Integer.toString(vout.getN()));
                    }
                });
                putVertex(vout_vertex);
                
                // Vout Edge
                WasGeneratedBy vout_edge = new WasGeneratedBy(vout_vertex, tx_node);
                vout_edge.addAnnotation("transactionValue", Double.toString(vout.getValue()));
                putEdge(vout_edge);

                // adresses
                for (final String address: vout.getAddresses()) {
                    Agent address_vertex = new Agent();
                    address_vertex.addAnnotations(new HashMap<String, String>(){
                    {
                        put("address", address);
                    }
                    });
                    putVertex(address_vertex); 

                    WasAttributedTo address_edge = new WasAttributedTo(vout_vertex, address_vertex);
                    putEdge(address_edge);
                }
            }
        }

        if (last_block_node==null) {
            // TODO: the edge between first block generated with bitcoin reporter and last block from bitcoin importer
            // get block from db
            // Graph graph = new Graph().getVertices("blockHeight:" + block.getHeight() );

            // last_block_node = (Activity) graph.getVertex(0);

            // System.out.println("duh + " + last_block_node.getAnnotation("blockHash"));
            // last_block_node = new Activity();
            // last_block_node.addAnnotations(new HashMap<String, String>(){
            //     {
            //         put("blockHash", block.getHash()); 
            //         put("blockHeight", Integer.toString(block.getHeight()));
            //         put("blockConfirmations", Integer.toString(block.getConfirmations()));
            //         put("blockTime", Integer.toString(block.getTime())); 
            //         put("blockDifficulty", Integer.toString(block.getDifficulty())); 
            //         put("blockChainwork", block.getChainwork());
            //     }
            // });

        }
        if (last_block_node!=null) {
            WasInformedBy block_edge = new WasInformedBy(block_node, last_block_node);
            putEdge(block_edge);

        }
        last_block_node = block_node;

    }
    
    void runner(int start_block, int end_block) {
        // init
        block_reader = new RPCManager();
        
        date =  Calendar.getInstance().getTime();
        
        int last_block=-1;
        if (start_block != -1) { // set start block to user input
            last_block  = start_block-1;
        }
        else { // or pick from last shutdown
            try {
                // block_hash_db = block_hashes.loadBlockHashes();
                last_block = getLastBlockProcessedFromCache();
                // we reprocess the last block from last run 
                // so that we can create the edge between this block 
                // and the next block that will be created
                if (last_block!=-1) {
                    last_block--;
                }
            } catch (Exception e) {
                Bitcoin.log(Level.SEVERE, "Couldn't Initialize. Quiting", e);
                return;
            }
        }

        // progress loop
        while (!shutdown) {
            if (end_block <= last_block && end_block != -1) { // user set end block reached
                Bitcoin.log(Level.INFO, "Last block pushed in database. You can detach reporter and database safely.", null);
                break;
            } 

            Block block = null;
            try {
                block = block_reader.getBlock(last_block+1);
            } catch (Exception e) {
                // either the block does not exist or server call fail. Wait and retry
                try {
                    Thread.sleep(pause_time); // wait for 1 sec
                } catch (Exception e1) {
                    Bitcoin.log(Level.SEVERE, "Failure to get new hashes from server. Quiting", e);
                    return;
                } 
                continue;
            }
            
            reportBlock(block);
            reportProgress(block);
            
            try {
                writeBlockProgressToCache(last_block+1);
            } catch (Exception e) {
                // shouldn't happen
            }
            last_block++;
        }
        
    }
    
    public static void log(Level level, String msg, Throwable thrown) {
        if (level == level.FINE) {
            // all FINE level logs are useful for auditing post processing.
        } else {
            // Logger.getLogger(Bitcoin.class.getName()).log(level, msg, thrown); 
            System.out.println(msg);
        }
    }
}