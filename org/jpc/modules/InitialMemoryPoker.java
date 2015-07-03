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

package org.jpc.modules;

import org.jpc.emulator.*;
import org.jpc.emulator.memory.*;
import java.io.*;
import java.util.*;

public class InitialMemoryPoker extends AbstractHardwareComponent
{
    private Clock clock;
    private PhysicalAddressSpace memory;
    private Map<Integer,Byte> bytesToPoke;

    public void reset()
    {
    }

    public void dumpSRPartial(SRDumper output) throws IOException
    {
        super.dumpSRPartial(output);
        output.dumpObject(clock);
        output.dumpObject(memory);
        Iterator<Map.Entry<Integer,Byte>> i = bytesToPoke.entrySet().iterator();
        while(i.hasNext()) {
            Map.Entry<Integer,Byte> e = i.next();
            output.dumpBoolean(true);
            output.dumpInt(e.getKey());
            output.dumpByte(e.getValue());
        }
        output.dumpBoolean(false);
    }

    public InitialMemoryPoker(SRLoader input) throws IOException
    {
        super(input);
        clock = (Clock)input.loadObject();
        memory = (PhysicalAddressSpace)input.loadObject();
        bytesToPoke = new HashMap<Integer,Byte>();
        while(input.loadBoolean()) {
            int addr = input.loadInt();
            byte val = input.loadByte();
            bytesToPoke.put(addr, val);
        }
    }

    static private int hexValue(char v) throws IOException
    {
        if(v >= '0' && v <= '9')
            return v - '0';
        if(v >= 'A' && v <= 'F')
            return v - 'A' + 10;
        if(v >= 'a' && v <= 'f')
            return v - 'a' + 10;
        throw new IOException("Invalid hex character");
    }

    static private int parseAddress(String addr) throws IOException
    {
        int r = 0;
        for(int i = 0; i < addr.length(); i++)
            r = 16 * r + hexValue(addr.charAt(i));
        return r;
    }

    static private byte[] parseValue(String val) throws IOException
    {
        if((val.length() % 2) > 0)
            throw new IOException("Value must be even length");
        byte[] r = new byte[val.length() / 2];
        for(int i = 0; i < val.length(); i+=2)
            r[i / 2] = (byte)(16 * hexValue(val.charAt(i)) + hexValue(val.charAt(i+1)));
        return r;
    }

    public InitialMemoryPoker(String parameters) throws IOException
    {
        clock = null;
        memory = null;
        bytesToPoke = new HashMap<Integer,Byte>();
        for(String el : parameters.split(",")) {
            String[] _el = el.split(":");
            if(_el.length != 2)
                throw new IOException("Exactly one : required in each component");
            int addr = parseAddress(_el[0]);
            byte[] bytes = parseValue(_el[1]);
            for(int i = 0; i < bytes.length; i++)
                bytesToPoke.put(addr + i, bytes[i]);
        }
    }

    public InitialMemoryPoker() throws IOException
    {
        this("");
    }

    public void dumpStatusPartial(StatusDumper output)
    {
        super.dumpStatusPartial(output);
        output.println("\tclock <object #" + output.objectNumber(clock) + ">"); if(clock != null) clock.dumpStatus(output);
        output.println("\tmemory <object #" + output.objectNumber(memory) + ">"); if(memory != null) memory.dumpStatus(output);
        Iterator<Map.Entry<Integer,Byte>> i = bytesToPoke.entrySet().iterator();
        while(i.hasNext()) {
            Map.Entry<Integer,Byte> e = i.next();
            output.println("\tbytesToPoke[" + e.getKey() + "]=" + e.getValue());
        }
    }

    public void dumpStatus(StatusDumper output)
    {
        if(output.dumped(this))
            return;

        output.println("#" + output.objectNumber(this) + ": InitialMemoryPoker:");
        dumpStatusPartial(output);
        output.endObject();
    }

    public boolean initialised()
    {
        return ((memory != null) && (clock != null));
    }

    private void doPoke()
    {
        if(initialised() && clock.getTime() == 0) {
            Iterator<Map.Entry<Integer,Byte>> i = bytesToPoke.entrySet().iterator();
            while(i.hasNext()) {
                Map.Entry<Integer,Byte> e = i.next();
                int addr = e.getKey();
                byte val = e.getValue();
                memory.pokeRAM(addr, val);
            }
        }
    }

    public void acceptComponent(HardwareComponent component)
    {
        if(clock == null && (component instanceof Clock) && component.initialised()) {
            clock = (Clock)component;
            doPoke();
        }
        if(memory == null && (component instanceof PhysicalAddressSpace) && component.initialised()) {
            memory = (PhysicalAddressSpace)component;
            doPoke();
        }
    }
}
