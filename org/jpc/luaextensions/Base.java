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

package org.jpc.luaextensions;

import mnj.lua.*;

import org.jpc.emulator.Clock;
import org.jpc.emulator.PC;
import org.jpc.emulator.pci.peripheral.VGACard;
import org.jpc.emulator.DisplayController;
import org.jpc.emulator.EventRecorder;
import org.jpc.emulator.motherboard.IntervalTimer;
import org.jpc.emulator.peripheral.Keyboard;
import org.jpc.plugins.LuaPlugin;

//Locking this class is used for preventing termination and when terminating.
public class Base extends LuaPlugin.LuaResource
{
    public void destroy()
    {
    }

    private Base(LuaPlugin plugin)
    {
        super(plugin);
    }

    private static int intLuaCBInvoke(Lua l, boolean sync, LuaPlugin plugin)
    {
        LuaTable args = null;
        String[] _args = null;
        int elements = 0;
        if(l.type(1) != Lua.TSTRING) {
            l.error("Unexpected types to invoke");
            return 0;
        }

        String cmd = l.value(1).toString();
        if(l.type(2) == Lua.TNONE || l.type(2) == Lua.TNIL) {
           ///Nothing.
        } else if(l.type(2) == Lua.TTABLE) {
           args = (LuaTable)l.value(2);
           elements = Lua.objLen(args);
           _args = new String[elements];
           for(int i = 0; i < elements; i++) {
               _args[i] = l.getTable(args, new Double(i + 1)).toString();
               if(_args[i] == null)
                   _args[i] = "";
           }
        } else {
            l.error("Unexpected types to invoke");
            return 0;
        }

        plugin.callInvokeCommand(cmd, _args, sync);
        return 0;
    }

    public static int luaCB_call(Lua l, LuaPlugin plugin)
    {
        LuaTable args = null;
        String[] _args = null;
        int elements = 0;
        if(l.type(1) != Lua.TSTRING) {
            l.error("Unexpected types to invoke");
            return 0;
        }

        String cmd = l.value(1).toString();
        if(l.type(2) == Lua.TNONE || l.type(2) == Lua.TNIL) {
           ///Nothing.
        } else if(l.type(2) == Lua.TTABLE) {
           args = (LuaTable)l.value(2);
           elements = Lua.objLen(args);
           _args = new String[elements];
           for(int i = 0; i < elements; i++) {
               _args[i] = l.getTable(args, new Double(i + 1)).toString();
               if(_args[i] == null)
                   _args[i] = "";
           }
        } else {
            l.error("Unexpected types to invoke");
            return 0;
        }

        Object[] ret = plugin.callCommand(cmd, _args);
        if(ret == null) {
            //System.err.println("No return values");
            return 0;
        }
        int i = 1;
        //System.err.println("" + ret.length + " return value(s)");
        LuaTable tab = l.newTable();
        for(Object retE : ret) {
            if(retE == null)
                i++;
            else if(retE instanceof Boolean)
                l.setTable(tab, new Double(i++), retE);
            else if(retE instanceof Integer)
                l.setTable(tab, new Double(i++), new Double(((Integer)retE).intValue()));
            else if(retE instanceof Long)
                l.setTable(tab, new Double(i++), new Double(((Long)retE).longValue()));
            else if(retE instanceof String)
                l.setTable(tab, new Double(i++), retE);
            else
                l.setTable(tab, new Double(i++), "<unconvertable object>");
        }
        l.push(tab);
        return 1;
    }

    public static int luaCB_invoke(Lua l, LuaPlugin plugin)
    {
        return intLuaCBInvoke(l, false, plugin);
    }

    public static int luaCB_invoke_synchronous(Lua l, LuaPlugin plugin)
    {
        return intLuaCBInvoke(l, true, plugin);
    }

    public static int luaCB_release_vga(Lua l, LuaPlugin plugin)
    {
        plugin.doReleaseVGA();
        return 0;
    }

    public static int luaCB_wait_event(Lua l, LuaPlugin plugin)
    {
        LuaPlugin.Event msg = plugin.waitEvent();
        int pushed = 1;
        if(msg != null) {
            l.push(msg.type);
            if(msg.data != null) {
                l.push(msg.data);
                pushed++;
            }
        } else
            l.pushNil();
        return pushed;
    }

    public static int luaCB_poll_message(Lua l, LuaPlugin plugin)
    {
        LuaPlugin.Event msg = plugin.pollEvent();
        int pushed = 1;
        if(msg != null) {
            l.push(msg.type);
            if(msg.data != null) {
                l.push(msg.data);
                pushed++;
            }
        } else
            l.pushNil();
        return pushed;
    }

    public static int luaCB_in_frame_hold(Lua l, LuaPlugin plugin)
    {
        l.pushBoolean(plugin.getOwnsVGALock());
        return 1;
    }

    public static int luaCB_stringlessthan(Lua l, LuaPlugin plugin)
    {
        int ret = 0;
        String x = l.value(1).toString();
        String y = l.value(2).toString();
        int xlen = x.length();
        int ylen = y.length();
        for(int i = 0; i < xlen && i < ylen; i++) {
            int xcp = x.charAt(i);
            int ycp = y.charAt(i);
            boolean xs = (xcp >= 0xD800 && xcp <= 0xDFFF);
            boolean ys = (ycp >= 0xD800 && ycp <= 0xDFFF);
            if(!xs && ys)
                ret = (ret == 0) ? 1 : ret;
            else if(xs && !ys)
                ret = (ret == 0) ? -1 : ret;
            else if(xcp < ycp)
                ret = (ret == 0) ? 1 : ret;
            else if(xcp > ycp)
                ret = (ret == 0) ? -1 : ret;
        }
        if(ret == 0 && xlen < ylen)
            ret = 1;
        if(ret == 0 && xlen > ylen)
            ret = -1;

        l.pushBoolean(ret == 1);
        return 1;
    }

    public static int luaCB_hold_mouse_motion(Lua l, LuaPlugin plugin)
    {
        Keyboard kbd = ((Keyboard)plugin.getComponent(Keyboard.class));
        if(kbd == null)
            return 0;
        int x = (int)l.checkNumber(1);
        int y = (int)l.checkNumber(2);
        int z = (l.type(3) == Lua.TNUMBER) ? (int)l.checkNumber(3) : 0;
        kbd.mouseSetHolds(x, y, z);
        return 0;
    }
}
