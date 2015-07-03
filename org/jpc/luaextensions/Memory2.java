/*
    JPC-RR: A x86 PC Hardware Emulator
    Release 1

    Copyright (C) 2007-2009 Isis Innovation Limited
    Copyright (C) 2009-2013 H. Ilari Liusvaara

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

import org.jpc.emulator.memory.PhysicalAddressSpace;
import org.jpc.emulator.processor.fpu64.FpuState64;
import org.jpc.plugins.LuaPlugin;
import java.io.*;
import java.util.*;


//Locking this class is used for preventing termination and when terminating.
public class Memory2 extends LuaPlugin.LuaResource
{

    public Memory2(LuaPlugin plugin) throws IOException
    {
        super(plugin);
    }

    public void destroy() throws IOException
    {
    }

    public static int luaCB_read_stride(Lua l, LuaPlugin plugin)
    {
        PhysicalAddressSpace mem = (PhysicalAddressSpace)plugin.getComponent(PhysicalAddressSpace.class);
        if(mem == null)
            return 0;
        int base = (int)l.checkNumber(1);
        int stride = (int)l.checkNumber(2);
        int count = (int)l.checkNumber(3);
        int type = (int)l.checkNumber(4);
        LuaTable ret = l.newTable();
        switch(type) {
        case 0:
            for(int i = 0; i < count; i++)
               l.setTable(ret, new Double(i + 1), new Double(mem.getByte(base + i * stride)));
            break;
        case 1:
            for(int i = 0; i < count; i++)
               l.setTable(ret, new Double(i + 1), new Double((long)mem.getByte(base + i * stride) & 0xFF));
            break;
        case 2:
            for(int i = 0; i < count; i++)
               l.setTable(ret, new Double(i + 1), new Double(mem.getWord(base + i * stride)));
            break;
        case 3:
            for(int i = 0; i < count; i++)
               l.setTable(ret, new Double(i + 1), new Double((long)mem.getWord(base + i * stride) & 0xFFFF));
            break;
        case 4:
            for(int i = 0; i < count; i++)
               l.setTable(ret, new Double(i + 1), new Double(mem.getDoubleWord(base + i * stride)));
            break;
        case 5:
            for(int i = 0; i < count; i++)
               l.setTable(ret, new Double(i + 1), new Double((long)mem.getDoubleWord(base + i * stride) &
                   0xFFFFFFFF));
            break;
        case 6:
            for(int i = 0; i < count; i++)
               l.setTable(ret, new Double(i + 1), new Double(Float.intBitsToFloat(mem.getDoubleWord(base + i *
                   stride))));
            break;
        case 7:
            for(int i = 0; i < count; i++)
               l.setTable(ret, new Double(i + 1), new Double(Double.longBitsToDouble(mem.getQuadWord(base + i *
                   stride))));
            break;
        }
        l.push(ret);
        return 1;
    }
}
