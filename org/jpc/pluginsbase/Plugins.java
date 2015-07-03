/*
    JPC-RR: A x86 PC Hardware Emulator
    Release 1

    Copyright (C) 2007-2009 Isis Innovation Limited
    Copyright (C) 2009-2010 H. Ilari Liusvaara

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

package org.jpc.pluginsbase;

import java.util.*;
import org.jpc.emulator.*;
import org.jpc.output.*;
import java.lang.reflect.*;
import static org.jpc.Misc.errorDialog;

public class Plugins
{
    private Set<Plugin> plugins;
    private Set<Plugin> nonRegisteredPlugins;
    private IdentityHashMap<Plugin, List<Object> > slaveObjects;
    private boolean manualShutdown;
    private boolean shutDown;
    private boolean commandComplete;
    private volatile boolean shuttingDown;
    private volatile boolean emulatorKill;
    private volatile boolean running;
    private volatile boolean valueReturned;
    private volatile Object[] returnValueObj;
    private PC currentPC;
    private OutputStatic outputConnector;
    private List<Object> renderers;

    //Create plugin manager.
    public Plugins()
    {
        plugins = new HashSet<Plugin>();
        nonRegisteredPlugins = new HashSet<Plugin>();
        slaveObjects = new IdentityHashMap<Plugin, List<Object>>();
        Runtime.getRuntime().addShutdownHook(new ShutdownHook());
        manualShutdown = true;
        shutDown = false;
        shuttingDown = false;
        running = false;
        outputConnector = new OutputStatic();
        renderers = new ArrayList<Object>();
    }

    public void addRenderer(Object renderer)
    {
        renderers.add(renderer);
    }

    public List<Object> getRenderers()
    {
        return renderers;
    }

    public OutputStatic getOutputConnector()
    {
        return outputConnector;
    }

    public boolean isShuttingDown()
    {
        return shuttingDown;
    }

    //Shut down and exit the emulator program.
    public void shutdownEmulator()
    {
        boolean doAgain = true;
        shuttingDown = true;
        Set<Plugin> plugins2 = new HashSet<Plugin>();

        if(shutDown)
            return;

        while(doAgain) {
            doAgain = false;
            for(Plugin plugin : plugins) {
                System.err.println("Informational: Shutting down " + plugin.getClass().getName() + "...");
                if(plugin.systemShutdown())
                    System.err.println("Informational: Shut down " + plugin.getClass().getName() + ".");
                else {
                    doAgain = true;
                    plugins2.add(plugin);
                }
            }
            plugins = plugins2;
        }
        shutDown = true;
        if(manualShutdown)
            System.exit(0);
    }

    //Signal reconnect event to all plugins.
    public synchronized void reconnect(PC pc)
    {
        if(currentPC != null)
            currentPC.getOutputs().setStaticOutput(null, 0);
        currentPC = pc;
        if(currentPC != null)
            currentPC.getOutputs().setStaticOutput(outputConnector, outputConnector.getLastTime() - pc.getTime());
        running = false;

        //All non-registered plugins become registered as we will recconnect them.
        plugins.addAll(nonRegisteredPlugins);
        nonRegisteredPlugins.clear();

        for(Plugin plugin : plugins) {
            System.err.println("Informational: Reconnecting " + plugin.getClass().getName() + "...");
            plugin.reconnect(pc);
            System.err.println("Informational: Reconnected " + plugin.getClass().getName() + "...");
        }
    }

    //Signal pc stop event to all plugins.
    public synchronized void pcStopped()
    {
        for(Plugin plugin : plugins) {
            plugin.pcStopping();
        }


        for(Plugin plugin : nonRegisteredPlugins) {
            System.err.println("Informational: Reconnecting " + plugin.getClass().getName() + "...");
            plugin.reconnect(currentPC);
            System.err.println("Informational: Reconnected " + plugin.getClass().getName() + "...");
        }
        //All non-registered plugins become registered as we recconnected them.
        plugins.addAll(nonRegisteredPlugins);
        nonRegisteredPlugins.clear();
        running = false;
    }

    //Signal pc start event to all plugins.
    public synchronized void pcStarted()
    {
        for(Plugin plugin : plugins) {
            plugin.pcStarting();
        }
        running = true;
    }

    private final boolean reinterpretable(Class<?> type, Object argument)
    {
        if(argument == null)
            return true;
        if(argument.getClass() == type)
            return true;
        if(type == String.class)
            return true;
        if(type == Integer.class) {
            if(argument.toString().matches("0x[0-9a-fA-F]+")) {
                //Parse as hexadecimal.
                try {
                    Integer.valueOf(argument.toString().substring(2), 16);
                    return true;
                } catch(NumberFormatException e) {
                    return false;
                }
            }
            try {
                Integer.decode(argument.toString());
                return true;
            } catch(NumberFormatException e) {
                return false;
            }
        }
        if(type == Long.class) {
            if(argument.toString().matches("0x[0-9a-fA-F]+")) {
                //Parse as hexadecimal.
                try {
                    Long.valueOf(argument.toString().substring(2), 16);
                    return true;
                } catch(NumberFormatException e) {
                    return false;
                }
            }
            try {
                Long.decode(argument.toString());
                return true;
            } catch(NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    private final boolean methodOk(Method method, Object[] args)
    {
        Class<?>[] argumentTypes = method.getParameterTypes();
        if(argumentTypes.length == 0)
            return (args == null || args.length == 0);
        int argIterator = 0;
        for(int i = 0; i < argumentTypes.length; i++) {
            Class<?> subType = argumentTypes[i].getComponentType();
            if(subType == null) {
                if(argIterator < args.length) {
                    if(!reinterpretable(argumentTypes[i], args[argIterator++]))
                        return false;
                } else
                    return false;
            } else if(argIterator == args.length) {
            } else
                for(int j = 0; j < args.length - argIterator; j++)
                    if(!reinterpretable(subType, args[argIterator++]))
                        return false;
        }
        return true;
    }

    private final boolean namesMatch(String cmd, String method)
    {
        if(!method.startsWith("eci_"))
            return false;
        cmd = cmd.replaceAll("-", "_");
        return method.substring(4).equals(cmd);
    }

    private final Method chooseMethod(Class<?> clazz, String cmd, Object[] args)
    {
        for(Method method : clazz.getDeclaredMethods())
            if(namesMatch(cmd, method.getName()) && methodOk(method, args)) {
                return method;
            }
        return null;
    }

    private final Object reinterpretToType(Class<?> type, Object argument)
    {
        //FIXME: Add more cases.
        if(argument == null)
            return null;
        if(argument.getClass() == type)
            return argument;
        if(type == String.class)
            return argument.toString();
        else if(type == Integer.class) {
            if(argument.toString().matches("0x[0-9a-fA-F]+")) {
                //Parse as hexadecimal.
                try {
                    return Integer.valueOf(argument.toString().substring(2), 16);
                } catch(NumberFormatException e) {
                    return null;
                }
            }
            try {
                return new Integer(Integer.decode(argument.toString()));
            } catch(NumberFormatException e) {
                return null; //Doesn't convert.
            }
        } else if(type == Long.class) {
            if(argument.toString().matches("0x[0-9a-fA-F]+")) {
                //Parse as hexadecimal.
                try {
                    return Long.valueOf(argument.toString().substring(2), 16);
                } catch(NumberFormatException e) {
                    return null;
                }
            }
            try {
                return new Long(Long.decode(argument.toString()));
            } catch(NumberFormatException e) {
                return null; //Doesn't convert.
            }
        } else {
            return null; //Reinterpretation not possible.
        }
    }

    private final Object[] prepareArguments(Method method, Object[] args)
    {
        Class<?>[] argumentTypes = method.getParameterTypes();
        Object[] ret = new Object[argumentTypes.length];
        if(argumentTypes.length == 0)
            return null;
        int argIterator = 0;
        for(int i = 0; i < argumentTypes.length; i++) {
            Class<?> subType = argumentTypes[i].getComponentType();
            if(subType == null) {
                if(argIterator < args.length)
                    ret[i] = reinterpretToType(argumentTypes[i], args[argIterator++]);
                else {
                    System.err.println("Warning: Ran out of arguments for ECI (incorrect method array argument?).");
                    ret[i] = null;
                }
            } else if(argIterator == args.length) {
                ret[i] = null;
            } else {
                int elts = args.length - argIterator;
                ret[i] = Array.newInstance(subType, elts);
                for(int j = 0; j < elts; j++)
                    Array.set(ret[i], j, reinterpretToType(subType, args[argIterator++]));
            }
        }
        return ret;
    }

    private final boolean invokeCommand(Object plugin, String cmd, Object[] args, boolean synchronous)
    {
        boolean done = false;
        boolean inherentlySynchronous = false;
        Class<?> targetClass = plugin.getClass();
        Method choosenMethod = null;
        Object[] callArgs = null;

        choosenMethod = chooseMethod(targetClass, cmd, args);
        commandComplete = false;

        if(choosenMethod != null) {
            callArgs = prepareArguments(choosenMethod, args);
            if(choosenMethod.getReturnType() == void.class) {
                try {
                    choosenMethod.invoke(plugin, callArgs);
                    done = true;
                } catch(InvocationTargetException e) {
                    errorDialog(e.getCause(), "Error in ECI method", null, "Ignore");
                } catch(Exception e) {
                    System.err.println("Error calling ECI method: " + e.getMessage());
                }
                inherentlySynchronous = true;
            } else if(choosenMethod.getReturnType() == boolean.class) {
                Object ret = null;
                try {
                    ret = choosenMethod.invoke(plugin, callArgs);
                    done = true;
                } catch(InvocationTargetException e) {
                    errorDialog(e.getCause(), "Error in ECI method", null, "Ignore");
                } catch(Exception e) {
                    System.err.println("Error calling ECI method: " + e.getMessage());
                }
                if(ret != null && ret instanceof Boolean)
                    inherentlySynchronous = !(((Boolean)ret).booleanValue());
                else
                    inherentlySynchronous = true;
            } else {
                System.err.println("Error: Bad return type '" + choosenMethod.getReturnType() + "' for ECI.");
                inherentlySynchronous = true; //Bad calls are always synchronous.
            }
        } else {
            inherentlySynchronous = true; //Bad calls are always synchronous.
        }

        while(synchronous && !inherentlySynchronous && !commandComplete)
            try {
                synchronized(this) {
                    wait();
                }
            } catch(Exception e) {
            }
        return done;
    }

    //Invoke the external command interface.
    public void invokeExternalCommand(String cmd, Object[] args)
    {
        boolean done = false;
        for(Plugin plugin : plugins) {
            done = invokeCommand(plugin, cmd, args, false) || done;
            List<Object> slaves = slaveObjects.get(plugin);
            if(slaves != null)
                for(Object slave : slaves)
                    done = invokeCommand(slave, cmd, args, false) || done;
        }
        if(!done)
            System.err.println("Warning: ECI invocation '" + cmd +  "' not delivereble.");
    }

    //Invoke the external command interface.
    public void invokeExternalCommandSynchronous(String cmd, String[] args)
    {
        boolean done = false;
        for(Plugin plugin : plugins) {
            done = invokeCommand(plugin, cmd, args, true) || done;
            List<Object> slaves = slaveObjects.get(plugin);
            if(slaves != null)
                for(Object slave : slaves)
                    done = invokeCommand(slave, cmd, args, true) || done;
        }
        if(!done)
            System.err.println("Warning: Synchronous ECI invocation '" + cmd +  "' not delivereble.");
    }

    //Invoke the external command interface.
    public synchronized Object[] invokeExternalCommandReturn(String cmd, String[] args)
    {
        valueReturned = false;
        returnValueObj = null;
        for(Plugin plugin : plugins) {
            invokeCommand(plugin, cmd, args, true);
            if(valueReturned)
                return returnValueObj;
            List<Object> slaves = slaveObjects.get(plugin);
            if(slaves != null)
                for(Object slave : slaves) {
                    invokeCommand(slave, cmd, args, true);
                    if(valueReturned)
                        return returnValueObj;
                }
        }
        System.err.println("Warning: ECI call '" + cmd +  "' not delivereble.");
        return null;
    }

    //Signal completion of command.
    public synchronized void returnValue(Object... ret)
    {
        returnValueObj = ret;
        valueReturned = true;
        commandComplete = true;
        notifyAll();
    }

    //Signal completion of command.
    public synchronized void signalCommandCompletion()
    {
        commandComplete = true;
        notifyAll();
    }

    //Make object slave of some plugin.
    public synchronized void addSlaveObject(Plugin plugin, Object slave)
    {
        if(slaveObjects.get(plugin) == null)
            slaveObjects.put(plugin, new ArrayList<Object>());
        List<Object> slaves = slaveObjects.get(plugin);
        slaves.add(slave);
    }

    //Add new plugin and invoke main thread for it.
    public synchronized void registerPlugin(Plugin plugin)
    {
        if(currentPC == null || !running)
            plugins.add(plugin);
        else
            nonRegisteredPlugins.add(plugin);
        (new PluginThread(plugin)).start();

        if(currentPC != null && !running) {
            System.err.println("Informational: Reconnecting " + plugin.getClass().getName() + "...");
            plugin.reconnect(currentPC);
            System.err.println("Informational: Reconnected " + plugin.getClass().getName() + "...");
        }
    }

    public synchronized boolean unregisterPlugin(Plugin plugin)
    {
        if(nonRegisteredPlugins.contains(plugin)) {
            nonRegisteredPlugins.remove(plugin);
            System.err.println("Informational: Shutting down " + plugin.getClass().getName() + "...");
            plugin.systemShutdown();
            System.err.println("Informational: Shut down " + plugin.getClass().getName() + ".");
            slaveObjects.put(plugin, null);
            return true;
        } else {
            System.err.println("Informational: Shutting down " + plugin.getClass().getName() + "...");
            if(plugin.systemShutdown()) {
                System.err.println("Informational: Shut down " + plugin.getClass().getName() + ".");
                plugins.remove(plugin);
                slaveObjects.put(plugin, null);
                return true;
            } else {
                System.err.println("Error: " + plugin.getClass().getName() + " does not want to shut down.");
                return false;
            }
        }
    }

    public void doKillEmulator()
    {
        emulatorKill = true;
        System.exit(1);
    }

    private class ShutdownHook extends Thread
    {
        public void run()
        {
            if(emulatorKill)
                return;   //Ungraceful shutdown.
            manualShutdown = false;
            shutdownEmulator();
        }
    }

    private class PluginThread extends Thread
    {
        private Plugin plugin;

        public PluginThread(Plugin _plugin)
        {
            super("Plugin thread for " + _plugin.getClass().getName());
            plugin = _plugin;
        }

        public void run()
        {
            plugin.main();
        }
    }
}
