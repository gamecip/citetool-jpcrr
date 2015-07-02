package org.jpc.plugins;

/**
 * Created by erickaltman on 6/30/15.
 */

//From JPC-RR
import static org.jpc.Misc.parseStringToComponents;
import org.jpc.emulator.PC;
import org.jpc.pluginsbase.Plugin;
import org.jpc.pluginsbase.Plugins;
import org.jpc.Revision;

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
        //Nothing here yet.
    }

    public void pcStopping()
    {
        //Nothing here yet.
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

        public String processInput(String inputString)
        {
            String outputString = null;

            if(initConnect)
            {
                //Generate system status information
                outputString = generateSourceInfo();
                initConnect = false;
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
