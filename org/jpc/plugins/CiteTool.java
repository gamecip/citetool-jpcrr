package org.jpc.plugins;

/**
 * Created by erickaltman on 6/30/15.
 */

//From JPC-RR
import static org.jpc.Misc.parseStringToComponents;
import org.jpc.emulator.PC;
import org.jpc.jrsr.JRSRArchiveReader;
import org.jpc.pluginsbase.Plugin;
import org.jpc.pluginsbase.Plugins;
import org.jpc.Revision;
import static org.jpc.Misc.errorDialog;

//For encoding
import org.json.simple.JSONObject;

import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class CiteTool implements Plugin {

    private Map<String, String> serverArguments;
    private int port;
    private PC pc;
    private boolean pcRunStatus;
    private Plugins pluginManager;

    public CiteTool(Plugins manager, String args) throws Exception
    {
        serverArguments = parseStringToComponents(args);
        port = Integer.parseInt(serverArguments.get("port"));
        pluginManager = manager;
    }

    public void reconnect(PC _pc)
    {
        pcRunStatus = false;
        pc = _pc;
    }

    public void pcStarting()
    {
        pcRunStatus = true;
    }

    public void pcStopping()
    {
        pcRunStatus = false;
    }

    public boolean systemShutdown()
    {
       return true;
    }

    public void main()
    {
        try(
                ServerSocket serverSocket = new ServerSocket(port);
                Socket clientSocket = serverSocket.accept();
                PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))
        )
        {
            String inputLine, outputLine;
            CiteToolProtocol ctp = new CiteToolProtocol();
            outputLine = ctp.processInput(null);
            out.println(outputLine);
            out.println(ctp.waitingForRequest);

            while((inputLine = in.readLine()) != null)
            {
                outputLine = ctp.processInput(inputLine);
                out.println(outputLine);
                if(ctp.available())
                {
                    out.println(ctp.waitingForRequest);
                }
            }

        } catch (IOException e)
        {
            System.out.println("Exception caught when trying to listen on port " + port + " or listening for a connection");
            System.out.println(e.getMessage());
        }
    }

    public synchronized void connectPC(PC pc)
    {
        pluginManager.reconnect(pc);
        this.pc = pc;
        notifyAll();
    }

    private class CiteToolProtocol
    {
        private static final int WAITING = 0;
        private static final int BUSY = 1;
        private boolean initConnect = true;
        private int state = WAITING;

        //Basic System information
        private final String emulatorSource = "jpc-rr";
        private final String emulatorVersion = Revision.getRelease();
        private final String emulatorRevision = Revision.getRevision();
        private List<String> protocolVersions = Arrays.asList("1");

        //Protocol Version 1 Commands
        private final String useVersion = "use_version";
        private final String loadSystem = "load_system";
        private final String loadInputs = "load_inputs";
        private final String framesRequest = "frames";
        private final String infoRequest = "info";
        private final String waitingForRequest = "ready";

        private String fileName;
        private String submovie;
        private boolean shutDown;
        private boolean shutDownRequest;
        private long imminentTrapTime;


        public String processInput(String inputString)
        {
            String outputString = null;
            Exception caught = null;

            if(initConnect)
            {
                //Generate system status information
                outputString = generateSourceInfo();
                initConnect = false;
            }else{
                if(inputString.equals("test_load")){
                    PC.PCFullStatus fullStatus = null;
                    try {
                        System.err.println("Informational: Loading a snapshot of JPC-RR");
                        JRSRArchiveReader reader = new JRSRArchiveReader("/Users/erickaltman/dev/projects/gamecip/emulators/citetool-jpcrr/movies/keen4_test_after_movie_load");
                        fullStatus = PC.loadSavestate(reader, false, false, null, submovie);
                        pc = fullStatus.pc;
                        reader.close();
                        fullStatus.events.setPCRunStatus(true);
                    } catch(Exception e) {
                        caught = e;
                    }

                    if(caught == null) {
                        try {
                            connectPC(pc);
                            System.err.println("Informational: Loadstate done");
                        } catch(Exception e) {
                            caught = e;
                        }
                    }

                    if(caught != null) {
                        System.err.println("Critical: Savestate load failed.");
                        errorDialog(caught, "Failed to load savestate", null, "Quit");
                        shutDown = true;
                        pluginManager.shutdownEmulator();
                        outputString = "There was an error";
                        return outputString;
                    }

                    pluginManager.pcStarted();
                    pc.start();
                    pc.refreshGameinfo(fullStatus);
                }
                outputString = "OK";
            }

            return outputString;
        }


        public boolean available()
        {
            return state == WAITING;
        }

        private String generateSourceInfo()
        {
            JSONObject obj = new JSONObject();
            obj.put("source", emulatorSource);
            obj.put("version", emulatorVersion);
            obj.put("revision", emulatorRevision);
            obj.put("protocol_versions", protocolVersions);
            return obj.toJSONString();
        }
    }
}
