/*
    JPC-RR: A x86 PC Hardware Emulator
    Release 1

    Copyright (C) 2007-2009 Isis Innovation Limited
    Copyright (C) 2009-2011 H. Ilari Liusvaara

    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License version 2 as published by
    the Free Software Foundation.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along
    with this program; if not, write to the Free Software Foundation, Inc.,
    51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.

    Based on JPC x86 PC Hardware emulator,
    A project from the Physics Dept, The University of Oxford

    Details about original JPC can be found at:

    www-jpc.physics.ox.ac.uk

*/

package org.jpc.plugins;

import mnj.lua.*;

import java.io.*;
import java.util.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import java.lang.reflect.*;
import org.jpc.Misc;

import org.jpc.output.*;
import org.jpc.emulator.PC;
import org.jpc.emulator.DisplayController;
import org.jpc.emulator.HardwareComponent;
import org.jpc.emulator.VGADigitalOut;
import org.jpc.pluginsbase.Plugins;
import org.jpc.pluginsbase.Plugin;
import static org.jpc.Misc.parseStringToComponents;
import static org.jpc.Misc.errorDialog;
import static org.jpc.Misc.moveWindow;
import static org.jpc.Misc.openStream;

//Locking this class is used for preventing termination and when terminating.
public class LuaPlugin implements ActionListener, Plugin
{
    private JFrame window;
    private JPanel panel;
    private Plugins vPluginManager;
    private String kernelName;
    private Map<String, String> kernelArguments;
    private Map<String, String> userArguments;
    private int nativeWidth;
    private int nativeHeight;
    private JLabel execLabel;
    private JTextField execName;
    private JButton execButton;
    private JButton termButton;
    private JButton clearButton;
    private JTextArea console;

    private int nextHandle;

    //luaThread is null if Lua isn't running.
    private Thread luaThread;
    private Lua luaState;
    private volatile boolean pcRunning;
    private volatile boolean inCall;
    private volatile String luaInvokeReq;
    private volatile boolean luaTerminateReq;
    private volatile boolean luaTerminateReqAsync;
    private OutputClient screenOut;
    private OutputStatic outputConnector;
    private PC pc;
    private volatile boolean ownsVGALock;
    private volatile boolean signalComplete;
    private volatile boolean luaStarted;
    private volatile boolean mainThreadWait;
    private volatile boolean reconnectInProgress;

    private VGARetraceWaiter vgaPoller;

    private boolean consoleMode;
    private boolean specialNoGUIMode;

    private Map<String, LuaResource> resources;
    private IdentityHashMap<LuaResource, Integer> liveObjects;

    public static abstract class LuaResource
    {
        String handle;
        LuaPlugin plugin;

        public LuaResource(LuaPlugin _plugin)
        {
            handle = "h" + (_plugin.nextHandle++);
            plugin = _plugin;
            plugin.resources.put(handle, this);
        }

        public final String getHandle()
        {
            return handle;
        }

        public final void release(boolean noExceptions) throws IOException
        {
            try {
                destroy();
            } catch(IOException e) {
                if(!noExceptions)
                    throw e;
            }
            plugin.liveObjects.remove(this);
            plugin.resources.remove(handle);
        }

        public abstract void destroy() throws IOException;
    }

    private String getMethodHandle(Lua l)
    {
        if(l.type(1) == Lua.TNONE) {
            l.error("Handle required for method call");
            return null;
        }
        l.checkType(1, Lua.TUSERDATA);
        Object _u = l.toUserdata(l.value(1)).getUserdata();
        if(!(_u instanceof String)) {
            if(_u != null) {
                l.error("Invalid handle to resource: " + _u.getClass().getName());
            } else {
                l.error("Invalid handle to resource: Null");
            }
            return null;
        }
        return (String)_u;
    }

    public void destroyLuaObject(Lua l) throws IOException
    {
        String u = getMethodHandle(l);
        LuaResource r1 = resources.get(u);
        if(r1 == null) {
            l.error("Bad or closed handle passed to method");
        }
        r1.release(false);
    }

    public boolean systemShutdown()
    {
        //Just terminate the emulator.
        return true;
    }

    private void reconnectBody(PC _pc)
    {
            reconnectInProgress = false;
            if(ownsVGALock) {
                screenOut.release();
                ownsVGALock = false;
            }
            if(screenOut != null) {
                screenOut.detach();
                screenOut = null;
            }
            pc = _pc;
            if(luaThread != null) {
                screenOut = new OutputClient(outputConnector);
                vgaPoller.reactivate();
            }
    }

    public void reconnect(PC _pc)
    {
        vgaPoller.deactivate();
        if(inCall) {
            //Assume its synchronized enough...
            reconnectBody(_pc);
        } else {
            //Get the event waiter out of way.
            reconnectInProgress = true;
            if(luaThread != null)
                luaThread.interrupt();
            synchronized(this) {
                reconnectBody(_pc);
            }
        }
        queueEvent("attach", null);
    }

    public void pcStarting()
    {
        pcRunning = true;
    }

    public void pcStopping()
    {
        pcRunning = false;
        queueEvent("stop", null);
    }

    class LuaCallback extends LuaJavaCallback
    {
        Method callbackMethod;
        Object onObject;

        LuaCallback(Object target, Method callback)
        {
            onObject = target;
            callbackMethod = callback;
        }

        public int luaFunction(Lua l) {
            synchronized(LuaPlugin.this) {
                try {
                    if(!liveObjects.containsKey(onObject)) {
                        l.error("Attempted to call method on dead object");
                        return 0;
                    } else
                        return ((Integer)callbackMethod.invoke(onObject, luaState, LuaPlugin.this)).intValue();
                } catch(InvocationTargetException e) {
                    if(e.getCause() instanceof LuaError)
                        throw (LuaError)e.getCause();   //Pass runtime exceptions through.
                    errorDialog(e.getCause(), "Error in callback", null, "Terminate Lua VM");
                    terminateLuaVMAsync();
                } catch(Exception e) {
                    if(e instanceof LuaError)
                        throw (LuaError)e;   //Pass runtime exceptions through.
                    errorDialog(e, "Error invoking callback", null, "Terminate Lua VM");
                    terminateLuaVMAsync();
                }
            }
            while(true);
        }
    }

    public void tableAddFunctions(Lua l, LuaTable table, Object obj, Class<?> clazz)
    {
        if(obj != null)
            clazz = obj.getClass();
        //Add all exported callbacks.
        Method[] candidateMethods = clazz.getMethods();
        for(Method candidate: candidateMethods) {
            if(obj != null && Modifier.isStatic(candidate.getModifiers()))
                continue;    //Want non-static.
            if(obj == null && !Modifier.isStatic(candidate.getModifiers()))
                continue;    //Want static.
            if(!Modifier.isPublic(candidate.getModifiers()))
                continue;    //Want public.
            if(!candidate.getName().startsWith("luaCB_"))
                continue;   //Not this...
            String luaName = candidate.getName().substring(6);
            Class<?>[] paramTypes = candidate.getParameterTypes();
            Class<?> retType = candidate.getReturnType();
            if(retType != int.class) {
                System.err.println("Warning: Incorrect return type for " + candidate.getName() +
                    ": " + retType.getName() + ".");
                continue;
            }
            if(paramTypes == null || paramTypes.length != 2) {
                System.err.println("Warning: Incorrect parameter type for " + candidate.getName() + ".");
                continue;
            }
            if(paramTypes[0] != Lua.class || paramTypes[1] != LuaPlugin.class) {
                System.err.println("Warning: Incorrect parameter type for " + candidate.getName() + ".");
                continue;
            }

            l.setTable(table, luaName, new LuaCallback(obj, candidate));
        }
    }

    public LuaUserdata generateLuaClass(Lua l, LuaResource towrap)
    {
        LuaUserdata user = new LuaUserdata(towrap.getHandle());
        LuaTable t = l.newTable();
        tableAddFunctions(l, t, towrap, null);
        liveObjects.put(towrap, null);
        l.setTable(t, "__index" , t);
        l.setMetatable(user, t);
        l.push(user);
        return user;
    }

    class LuaThread implements Runnable
    {
        Lua lua;
        String script;

        LuaThread(Lua _lua, String _script)
        {
            BaseLib.open(_lua);
            StringLib.open(_lua);
            MathLib.open(_lua);
            TableLib.open(_lua);
            lua = _lua;
            script = _script;
        }

        private String describeFault(int r)
        {
                if(r == 0)                  return null;
                else if(r == Lua.YIELD)     return "Main thread yielded.";
                else if(r == Lua.ERRRUN)    return "Unprotected runtime error";
                else if(r == Lua.ERRSYNTAX) return "syntax error";
                //else if(r == Lua.ERRMEM)  return "Out of memory");
                else if(r == Lua.ERRFILE)   return "I/O error loading";
                else if(r == Lua.ERRERR)    return "Double fault";
                else                        return "Unknown fault #" + r;
        }

        public void run()
        {
            LuaTable sTable;

            lua.setGlobal("script", script);

            lua.setGlobal("args", sTable = lua.newTable());
            for(Map.Entry<String, String> x : kernelArguments.entrySet())
                lua.setTable(sTable, x.getKey(), x.getValue());
            for(Map.Entry<String, String> x : userArguments.entrySet())
                lua.setTable(sTable, "x-" + x.getKey(), x.getValue());

            tableAddFunctions(lua, lua.getGlobals(), null, LuaPlugin.class);

            //Wait for lua startup to be signaled in order to avoid deadlocks.
            while(!luaStarted)
                try {
                    wait();
                } catch(Exception e) {
                }

            InputStream kernel = null;
            try {
                kernel = new BufferedInputStream(openStream(kernelName, "datafiles/luakernel"));
                int r = lua.load(kernel, "Kernel");
                String fault = describeFault(r);
                if(fault != null)
                    throw new Exception("Kernel loading error: " + fault);
                r = lua.pcall(0, 0, null);
                fault = describeFault(r);
                if(fault != null)
                    throw new Exception("Kernel error: " + fault);
            } catch(Exception e) {
                printConsoleMsg("\n\nLua Error: " + e.getMessage() + "\n" +
                    lua.value(-1).toString() + "\n\n");
                //e.printStackTrace();
                errorDialog(e, "Lua error", null, "Dismiss");
            }
            //Lua script quit. Terminate the VM.
            synchronized(LuaPlugin.this) {
                cleanupLuaResources();
                luaThread = null;
                luaState = null;
                LuaPlugin.this.notifyAll();
            }
            printConsoleMsg("Lua VM: Lua script finished.\n");
        }
    }

    private void cleanupLuaResources()
    {
        vgaPoller.deactivate();
        if(ownsVGALock) {
            screenOut.release();
            ownsVGALock = false;
        }
        if(screenOut != null) {
            screenOut.detach();
            screenOut = null;
        }

        while(resources.size() > 0) {
            Map.Entry<String, LuaResource> entry = resources.entrySet().iterator().next();
            String key = entry.getKey();
            LuaResource obj = entry.getValue();
            try {
                obj.release(true);
            } catch(Exception e) {
            }
            resources.remove(key);
            liveObjects.remove(obj);
        }
        resources.clear();
    }

    public void main()
    {
        while(true) {
            try {
                synchronized(this) {
                    mainThreadWait = true;
                    notifyAll();
                    if(luaInvokeReq == null && !luaTerminateReq)
                        wait();
                    mainThreadWait = false;
                }
            } catch(Exception e) {
                continue;
            }
            if(luaInvokeReq != null && luaThread == null) {
                //Run the Lua VM.
                eventQueue = new LinkedList<Event>();
                if(screenOut == null) {
                    screenOut = new OutputClient(outputConnector);
                    vgaPoller.reactivate();
                }
                luaStarted = false;
                luaState = new Lua();
                luaThread = new Thread(new LuaThread(luaState, luaInvokeReq), "Lua execution thread");
                luaThread.start();
                synchronized(this) {
                    luaInvokeReq = null;
                    signalComplete = true;
                    luaStarted = true;
                    notifyAll();
                }
            } else if(luaInvokeReq != null) {
                //Invoke request with Lua running? Shouldn't happen.
                System.err.println("Error: Lua invoke request with Lua running!");
                luaInvokeReq = null;
            } else if(luaTerminateReq && luaThread != null) {
                //This is fun... Terminate Lua VM. Sychronize in order to avoid terminating VM in
                //inapporiate place. And yes, that thread gets killed! The interrupt is to prevent
                //or kick the object from sleeping on VGA wait.
                luaThread.interrupt();
                synchronized(this) {
                    luaThread.stop();
                    cleanupLuaResources();
                    luaState = null;
                    luaThread = null;
                    luaTerminateReq = false;
                    signalComplete = true;
                    notifyAll();
                    if(luaTerminateReqAsync)
                        setLuaButtons();
                }
                printConsoleMsg("Lua VM: Lua VM terminated.\n");
                cleanupLuaResources();
            } else if(luaTerminateReq) {
                //Invoke request with Lua running? Shouldn't happen.
                System.err.println("Error: Lua terminate request with Lua not running!");
                luaTerminateReq = false;
                setLuaButtons();
            } else {
                setLuaButtons();
            }
        }
    }

    public void printConsoleMsg(String msg)
    {
        final String _msg = msg;

        if(consoleMode || specialNoGUIMode) {
            System.out.print(msg);
            return;
        }

        if(!SwingUtilities.isEventDispatchThread())
            try {
                //Do this async to avoid deadlocks with PCRunner stop.
                SwingUtilities.invokeLater(new Thread() { public void run() {
                    console.setText(console.getText() + _msg);
                }});
            } catch(Exception e) {
            }
        else
            console.setText(console.getText() + msg);
    }

    private synchronized void invokeLuaVM(String script) throws Exception
    {
        if(luaThread != null) {
            return;
        }

        int split = script.indexOf('(');
        if(split < 0) {
            userArguments = new HashMap<String, String>();
        } else {
            String arguments = script.substring(split + 1);
            int split2 = arguments.lastIndexOf(')');
            if(split2 != arguments.length() - 1)
                throw new Exception("Bad argument syntax");
            arguments = arguments.substring(0, split2);
            userArguments = parseStringToComponents(arguments);
        }

        //Starting from Lua itself is not possible.
        signalComplete = false;
        if(split < 0)
            luaInvokeReq = script;
        else
            luaInvokeReq = script.substring(0, split);
        luaTerminateReq = false;
        notifyAll();
        while(!signalComplete)
            try {
                wait();
            } catch(Exception e) {
            }

        setLuaButtons();
    }

    private synchronized void terminateLuaVM()
    {
        notifyAll();
        if(luaThread == null)
            return;

        //This request won't go to lua execution thread.
        signalComplete = false;
        luaTerminateReq = true;
        luaTerminateReqAsync = false;
        notifyAll();
        while(luaThread != null)
            try {
                wait();
            } catch(Exception e) {
            }

        setLuaButtons();
    }

    private synchronized void terminateLuaVMAsync()
    {
        if(luaThread == null)
            return;

        //This request won't go to lua execution thread.
        signalComplete = false;
        luaTerminateReq = true;
        luaTerminateReqAsync = true;
        notifyAll();
    }

    private void setLuaButtons()
    {
        if(consoleMode || specialNoGUIMode)
            return;

        if(!SwingUtilities.isEventDispatchThread())
            try {
                SwingUtilities.invokeAndWait(new Thread() { public void run() {
                    LuaPlugin.this.execButton.setText((luaThread == null) ? "Run" : "Send");
                    LuaPlugin.this.termButton.setEnabled(luaThread != null);
                }});
            } catch(Exception e) {
            }
        else {
            LuaPlugin.this.execButton.setText((luaThread == null) ? "Run" : "Send");
            LuaPlugin.this.termButton.setEnabled(luaThread != null);
        }
    }

    private void clearConsole()
    {
        if(consoleMode || specialNoGUIMode)
            return;

        if(!SwingUtilities.isEventDispatchThread())
            try {
                SwingUtilities.invokeAndWait(new Thread() { public void run() {
                    console.setText("");
                }});
            } catch(Exception e) {
            }
        else {
            console.setText("");
        }
    }

    public void actionPerformed(ActionEvent evt)
    {
        if(evt.getSource() == execButton) {
            try {
                if(luaThread == null)
                    invokeLuaVM(execName.getText());
                else
                    postMessage(execName.getText());
            } catch(Exception e) {
                printConsoleMsg("Lua script starting / message send error: " + e.getMessage());
            }
        } else if(evt.getSource() == termButton) {
            luaTerminateReq = true;
            if(luaThread != null)
                luaThread.interrupt();
            terminateLuaVM();
        } else if(evt.getSource() == clearButton) {
            clearConsole();
        }
    }

    public void eci_luaplugin_sendmessage(String x)
    {
        if(luaThread != null && x != null)
            postMessage(x);
    }

    public void eci_luaplugin_setwinpos(Integer x, Integer y)
    {
        moveWindow(window, x.intValue(), y.intValue(), nativeWidth, nativeHeight);
    }

    public void eci_luaplugin_run(String script)
    {
        if(luaThread == null)
            try {
                invokeLuaVM(script);
            } catch(Exception e) {
                printConsoleMsg("Lua script starting error: " + e.getMessage());
            }
    }

    public void eci_luaplugin_terminate()
    {
        luaTerminateReq = true;
        if(luaThread != null)
            luaThread.interrupt();
        terminateLuaVMAsync();
    }

    public void eci_luaplugin_clearconsole()
    {
        clearConsole();
    }

    private void invokeCommand(String cmd, String[] args)
    {
        if("luaplugin-terminate".equals(cmd) && args == null && luaThread != null) {
            luaTerminateReq = true;
            if(luaThread != null)
                luaThread.interrupt();
            terminateLuaVMAsync();
        }
    }

    public void callInvokeCommand(String cmd, String[] args, boolean sync)
    {
        try {
            inCall = true;
            if(consoleMode)
                invokeCommand(cmd, args);
            else if(sync)
                vPluginManager.invokeExternalCommandSynchronous(cmd, args);
            else
                vPluginManager.invokeExternalCommand(cmd, args);
        } finally {
            inCall = false;
        }
    }

    public Object[] callCommand(String cmd, String[] args)
    {
        try {
            inCall = true;
            if(consoleMode) {
                invokeCommand(cmd, args);
                return null;
            }
            return vPluginManager.invokeExternalCommandReturn(cmd, args);
        } finally {
            inCall = false;
        }
    }

    public HardwareComponent getComponent(Class<?> clazz)
    {
        if(pc == null)
            return null;
        return pc.getComponent(clazz);
    }

    public void postMessage(String msg)
    {
        queueEvent("message", msg);
    }

    public void doReleaseVGA()
    {
        if(screenOut != null && ownsVGALock)
            screenOut.release();
        ownsVGALock = false;
        vgaPoller.reactivate();
    }

    public boolean getOwnsVGALock()
    {
        return ownsVGALock;
    }

    public int getXResolution()
    {
        DisplayController dc = (DisplayController)getComponent(DisplayController.class);
        if(dc != null)
            return dc.getWidth();
        return -1;
    }

    public int getYResolution()
    {
        DisplayController dc = (DisplayController)getComponent(DisplayController.class);
        if(dc != null)
            return dc.getHeight();
        return -1;
    }

    public PC getPC()
    {
        return pc;
    }

    public boolean getPCConnected()
    {
        return (pc != null);
    }

    public boolean getPCRunning()
    {
        return pcRunning;
    }

    class VGARetraceWaiter extends Thread
    {
        private volatile boolean active;
        private volatile boolean deactivateFlag;

        public VGARetraceWaiter()
        {
            super("VGA Lua Trace waiting thread");
            active = false;
            deactivateFlag = false;
        }

        public void run()
        {
            while(true)
                if(active && screenOut != null) {
                    boolean r = screenOut.aquire();
                    if(r) {
                        ownsVGALock = true;
                        synchronized(this) {
                            queueEvent("lock", null);
                            active = false;
                            deactivateFlag = false;
                            notifyAll();
                        }
                    } else if(deactivateFlag) {
                        synchronized(this) {
                            active = false;
                            deactivateFlag = false;
                            notifyAll();
                        }
                    }
                } else {
                    synchronized(this) {
                        while(!active || screenOut == null) {
                            try {
                                wait();
                            } catch(Exception e) {
                            }
                            if(deactivateFlag) {
                                active = false;
                                deactivateFlag = false;
                                notifyAll();
                            }
                        }
                    }
                }
        }

        public void deactivate()
        {
            deactivateFlag = true;
            interrupt();
            synchronized(this) {
                notifyAll();
                while(active)
                    try {
                        wait();
                    } catch(Exception e) {
                    }
            }
        }

        public synchronized void reactivate()
        {
            active = true;
            notifyAll();
        }
    }

    public void queueEvent(String type, String data)
    {
        Event e = new Event();
        e.type = type;
        e.data = data;
        synchronized(eventQueue) {
            eventQueue.offer(e);
            eventQueue.notifyAll();
        }
    }

    public Event pollEvent()
    {
        synchronized(eventQueue) {
            return eventQueue.poll();
        }
    }

    public Event waitEvent()
    {
        synchronized(eventQueue) {
            Event e = null;
            while((e = eventQueue.poll()) == null && !luaTerminateReq && !reconnectInProgress)
                try {
                    eventQueue.wait();
                } catch(Exception f) {
                    return null;
                }
            return e;
        }
    }

    public class Event
    {
        public String type;
        public String data;
    };

    private Queue<Event> eventQueue;

    public LuaPlugin(String args) throws Exception
    {
        kernelArguments = parseStringToComponents(args);
        userArguments = new HashMap<String, String>();
        kernelName = kernelArguments.get("kernel");
        kernelArguments.remove("kernel");

        vgaPoller = new VGARetraceWaiter();
        vgaPoller.start();

        if(kernelArguments.get("noguimode") != null)
            this.specialNoGUIMode = true;

        this.pcRunning = false;
        this.luaThread = null;
        this.luaInvokeReq = null;
        this.luaTerminateReq = false;
        this.consoleMode = true;

        this.resources = new HashMap<String, LuaResource>();
        this.liveObjects = new IdentityHashMap<LuaResource, Integer>();
        this.eventQueue = new LinkedList<Event>();
        liveObjects.put(null, null);  //NULL is always considered live.
    }

    public LuaPlugin(Plugins manager, String args) throws Exception
    {
        this(args);

        this.consoleMode = false;
        this.vPluginManager = manager;
        this.outputConnector = manager.getOutputConnector();

        if(specialNoGUIMode)
            return;

        window = new JFrame("Lua window" + Misc.emuname);
        GridBagLayout layout = new GridBagLayout();
        GridBagConstraints c = new GridBagConstraints();
        panel = new JPanel(layout);
        window.add(panel);

        console = new JTextArea(25, 80);
        console.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane consoleScroller = new JScrollPane(console);
        console.setEditable(false);
        c.fill = GridBagConstraints.BOTH;
        c.gridwidth = 5;
        c.gridx = 0;
        c.gridy = 0;
        c.weighty = 1;
        panel.add(consoleScroller, c);

        execLabel = new JLabel("Lua script");
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        c.weighty = 0;
        c.gridwidth = 1;
        c.gridx = 0;
        c.gridy = 1;
        panel.add(execLabel, c);

        execName = new JTextField("", 40);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.gridwidth = 1;
        c.gridx = 1;
        c.gridy = 1;
        panel.add(execName, c);

        execButton = new JButton("Run");
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        c.gridwidth = 1;
        c.gridx = 2;
        c.gridy = 1;
        panel.add(execButton, c);
        execButton.addActionListener(this);

        termButton = new JButton("Terminate Lua VM");
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        c.gridwidth = 1;
        c.gridx = 3;
        c.gridy = 1;
        panel.add(termButton, c);
        termButton.addActionListener(this);
        termButton.setEnabled(false);

        clearButton = new JButton("Clear Console");
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        c.gridwidth = 1;
        c.gridx = 4;
        c.gridy = 1;
        panel.add(clearButton, c);
        clearButton.addActionListener(this);

        window.pack();
        window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        Dimension d = window.getSize();
        nativeWidth = d.width;
        nativeHeight = d.height;
        window.setVisible(true);
    }

    public java.util.List<Object> getRenderers()
    {
        return vPluginManager.getRenderers();
    }

    class DedicatedShutdownHandler extends Thread
    {
        public void run()
        {
            terminateLuaVM();
        }
    }

    class RunMainThread implements Runnable
    {
        public void run()
        {
            main();
        }
    }

    public static void main(String[] args)
    {
        if(args.length != 2) {
            System.err.println("Syntax: LuaPlugin <script> <args>");
            return;
        }


        LuaPlugin p;
        try {
            p = new LuaPlugin(args[1]);
        } catch(Exception e) {
            System.err.println("Can't initialize LuaPlugin: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        Runtime.getRuntime().addShutdownHook(p.new DedicatedShutdownHandler());
        Thread mThread = new Thread(p.new RunMainThread(), "Lua execution thread");
        mThread.start();

        synchronized(p) {
            //Wait for main thread to become ready and send invoke request.
            while(!p.mainThreadWait)
                try {
                    p.wait();
                } catch(Exception e) {
                }
            try {
                p.invokeLuaVM(args[0]);
            } catch(Exception e) {
                System.err.println("Error: Can't start Lua VM: " + e.getMessage());
            }

            //Wait for lua VM to finish.
            while(p.luaThread != null || p.luaInvokeReq != null)
                try {
                    p.wait();
                } catch(Exception e) {
                }
        }
        mThread.stop();
    }

    //Some extremely important callbacks.
    public static int luaCB_print_console_msg(Lua l, LuaPlugin plugin)
    {
        if(l.type(1) != Lua.TSTRING) {
            l.error("Unexpected types to print_console_msg");
            return 0;
        }
        plugin.printConsoleMsg(l.value(1).toString() + "\n");
        return 0;
    }

    public static int luaCB_loadmodule(Lua l, LuaPlugin plugin)
    {
        if(l.type(1) != Lua.TSTRING) {
            l.error("Unexpected types to loadmodule");
            return 0;
        }
        try {
            Class<?> clazz = Class.forName(l.checkString(1));
            LuaTable tab = l.newTable();
            plugin.tableAddFunctions(l, tab, null, clazz);
            l.push(tab);
        } catch(Exception e) {
            l.error("No such extension module: " + l.checkString(1));
            return 0;
        }
        return 1;
    }
}
