/*
    JPC: A x86 PC Hardware Emulator for a pure Java Virtual Machine
    Release Version 2.0

    A project from the Physics Dept, The University of Oxford

    Copyright (C) 2007 Isis Innovation Limited

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

    Details (including contact information) can be found at:

    www.physics.ox.ac.uk/jpc
*/

package org.jpc.emulator.memory;

import java.util.*;
import java.io.*;

import org.jpc.emulator.*;
import org.jpc.emulator.memory.codeblock.*;
import org.jpc.emulator.processor.*;

public final class LinearAddressSpace extends AddressSpace implements HardwareComponent
{
    private static final PageFaultWrapper PF_NOT_PRESENT_RU = new PageFaultWrapper(4);
    private static final PageFaultWrapper PF_NOT_PRESENT_RS = new PageFaultWrapper(0);
    private static final PageFaultWrapper PF_NOT_PRESENT_WU = new PageFaultWrapper(6);
    private static final PageFaultWrapper PF_NOT_PRESENT_WS = new PageFaultWrapper(2);

    private static final PageFaultWrapper PF_PROTECTION_VIOLATION_RU = new PageFaultWrapper(5);
    private static final PageFaultWrapper PF_PROTECTION_VIOLATION_RS = new PageFaultWrapper(1);
    private static final PageFaultWrapper PF_PROTECTION_VIOLATION_WU = new PageFaultWrapper(7);
    private static final PageFaultWrapper PF_PROTECTION_VIOLATION_WS = new PageFaultWrapper(3);

    private static final byte FOUR_M_GLOBAL = (byte) 0x03;
    private static final byte FOUR_M = (byte) 0x02;
    private static final byte FOUR_K_GLOBAL = (byte) 0x01;
    private static final byte FOUR_K = (byte) 0x00;

    private static final byte IS_GLOBAL_MASK = (byte) 0x1;
    private static final byte IS_4_M_MASK = (byte) 0x2;

    private boolean isSupervisor, globalPagesEnabled, pagingDisabled, pageCacheEnabled, writeProtectUserPages, pageSizeExtensions;
    private int baseAddress, lastAddress;
    private AddressSpace target;

    private byte[] pageFlags;
    private Hashtable<Integer, Integer> nonGlobalPages;
    private Memory[] readUserIndex, readSupervisorIndex, writeUserIndex, writeSupervisorIndex, readIndex, writeIndex;

    public LinearAddressSpace()
    {
        baseAddress = 0;
        lastAddress = 0;
        pagingDisabled = true;
        globalPagesEnabled = false;
        writeProtectUserPages = false;
        pageSizeExtensions = false;

        nonGlobalPages = new Hashtable<Integer, Integer>();

        pageFlags = new byte[INDEX_SIZE];
        for (int i=0; i < INDEX_SIZE; i++)
            pageFlags[i] = FOUR_K;

        readUserIndex = null;
        readSupervisorIndex = null;
        writeUserIndex = null;
        writeSupervisorIndex = null;
    }

    public void dumpStatusPartial(org.jpc.support.StatusDumper output)
    {
        super.dumpStatusPartial(output);
        output.println("\tisSupervisor " + isSupervisor + " globalPagesEnabled " + globalPagesEnabled);
        output.println("\tpagingDisabled " + pagingDisabled + " pageCacheEnabled " + pageCacheEnabled);
        output.println("\twriteProtectUserPages " + writeProtectUserPages + " pageSizeExtensions " + pageSizeExtensions);
        output.println("\tbaseAddress " + baseAddress + " lastAddress " + lastAddress);
        output.println("\ttarget <object #" + output.objectNumber(target) + ">"); target.dumpStatus(output);
        System.out.println("target dumped.");
        output.printArray(pageFlags, "pageFlags");
        System.out.println("pflags dumped.");
        output.println("\tnonGlobalPages:");
        Enumeration<Integer> ee = nonGlobalPages.keys();
        while (ee.hasMoreElements())
        {
            Integer key  = ee.nextElement();
            Integer value = nonGlobalPages.get(key);
            output.println("\t\t" + key.intValue() + " -> " + value.intValue());
        }
        dumpMemoryTableStatus(output, readUserIndex, "readUserIndex");
        dumpMemoryTableStatus(output, readSupervisorIndex, "readSupervisorIndex");
        dumpMemoryTableStatus(output, readIndex, "readIndex");
        dumpMemoryTableStatus(output, writeUserIndex, "writeUserIndex");
        dumpMemoryTableStatus(output, writeSupervisorIndex, "writeSupervisorIndex");
        dumpMemoryTableStatus(output, writeIndex, "writeIndex");
    }

    public void dumpStatus(org.jpc.support.StatusDumper output)
    {
        if(output.dumped(this))
            return;

        output.println("#" + output.objectNumber(this) + ": LinearAddressSpace:");
        dumpStatusPartial(output);
        output.endObject();
    }

    public static org.jpc.SRDumpable loadSR(org.jpc.support.SRLoader input, Integer id) throws IOException
    {
        org.jpc.SRDumpable x = new LinearAddressSpace(input);
        input.endObject();
        return x;
    }

    public void dumpSR(org.jpc.support.SRDumper output) throws IOException
    {
        if(output.dumped(this))
            return;
        dumpSRPartial(output);
        output.endObject();
    }

    public void dumpSRPartial(org.jpc.support.SRDumper output) throws IOException
    {
        super.dumpSRPartial(output);
        output.specialObject(PF_NOT_PRESENT_RU);
        output.specialObject(PF_NOT_PRESENT_RS);
        output.specialObject(PF_NOT_PRESENT_WU);
        output.specialObject(PF_NOT_PRESENT_WS);
        output.specialObject(PF_PROTECTION_VIOLATION_RU);
        output.specialObject(PF_PROTECTION_VIOLATION_RS);
        output.specialObject(PF_PROTECTION_VIOLATION_WU);
        output.specialObject(PF_PROTECTION_VIOLATION_WS);
        output.dumpBoolean(isSupervisor);
        output.dumpBoolean(globalPagesEnabled);
        output.dumpBoolean(pagingDisabled);
        output.dumpBoolean(pageCacheEnabled);
        output.dumpBoolean(writeProtectUserPages);
        output.dumpBoolean(pageSizeExtensions);
        output.dumpInt(baseAddress);
        output.dumpInt(lastAddress);
        output.dumpObject(target);
        output.dumpArray(pageFlags);
        Enumeration<Integer> ee = nonGlobalPages.keys();
        while (ee.hasMoreElements())
        {
            Integer key  = ee.nextElement();
            Integer value = nonGlobalPages.get(key);
            output.dumpBoolean(true);
            output.dumpInt(key.intValue());
            output.dumpInt(value.intValue());
        }
        output.dumpBoolean(false);
        dumpMemoryTableSR(output, readUserIndex);
        dumpMemoryTableSR(output, readSupervisorIndex);
        dumpMemoryTableSR(output, writeUserIndex);
        dumpMemoryTableSR(output, writeSupervisorIndex);
    }

    public LinearAddressSpace(org.jpc.support.SRLoader input) throws IOException
    {
        super(input);
        input.specialObject(PF_NOT_PRESENT_RU);
        input.specialObject(PF_NOT_PRESENT_RS);
        input.specialObject(PF_NOT_PRESENT_WU);
        input.specialObject(PF_NOT_PRESENT_WS);
        input.specialObject(PF_PROTECTION_VIOLATION_RU);
        input.specialObject(PF_PROTECTION_VIOLATION_RS);
        input.specialObject(PF_PROTECTION_VIOLATION_WU);
        input.specialObject(PF_PROTECTION_VIOLATION_WS);
        isSupervisor = input.loadBoolean();
        globalPagesEnabled = input.loadBoolean();
        pagingDisabled = input.loadBoolean();
        pageCacheEnabled = input.loadBoolean();
        writeProtectUserPages = input.loadBoolean();
        pageSizeExtensions = input.loadBoolean();
        baseAddress = input.loadInt();
        lastAddress = input.loadInt();
        target = (AddressSpace)(input.loadObject());
        pageFlags = input.loadArrayByte();
        nonGlobalPages = new Hashtable<Integer, Integer>();
        boolean nextNGPFlag = input.loadBoolean();
        while(nextNGPFlag) {
            Integer tNGPKey = new Integer(input.loadInt());
            Integer tNGPValue = new Integer(input.loadInt());
            nonGlobalPages.put(tNGPKey, tNGPValue);
            nextNGPFlag = input.loadBoolean();
        }
        readUserIndex = loadMemoryTableSR(input);
        readSupervisorIndex = loadMemoryTableSR(input);
        writeUserIndex = loadMemoryTableSR(input);
        writeSupervisorIndex = loadMemoryTableSR(input);
        if (isSupervisor)
        {
            readIndex = readSupervisorIndex;
            writeIndex = writeSupervisorIndex;
        }
        else
        {
           readIndex = readUserIndex;
           writeIndex = writeUserIndex;
        }
    }

    private Memory[] loadMemoryTableSR(org.jpc.support.SRLoader input) throws IOException
    {
        boolean dTablePresent = input.loadBoolean();
        if(!dTablePresent)
            return null;

        Memory[] mem = new Memory[input.loadInt()];
        byte[] presentMap = input.loadArrayByte();
        for(int i = 0; i < mem.length; i++)
            if((presentMap[i / 8] & (1 << (i % 8))) != 0) 
                mem[i] = (Memory)(input.loadObject());
        return mem;
    }


    private void dumpMemoryTableSR(org.jpc.support.SRDumper output, Memory[] mem) throws IOException
    {
        if(mem == null) {
            output.dumpBoolean(false);
        } else {
            output.dumpBoolean(true);
            output.dumpInt(mem.length);
            byte[] presentMap = new byte[(mem.length + 7) / 8];
            for(int i = 0; i < mem.length; i++)
                if(mem[i] != null) {
                    presentMap[i / 8] |= (1 << (i % 8));
                }
            output.dumpArray(presentMap);
            for(int i = 0; i < mem.length; i++)
                if(mem[i] != null)
                    output.dumpObject(mem[i]);            
        }
    }

    private void dumpMemoryTableStatus(org.jpc.support.StatusDumper output, Memory[] mem, String name)
    {
        if(mem == null) {
            output.println("\t" + name +" null");
        } else {
            for(int i = 0; i < mem.length; i++) {
                output.println("\t" + name + "[" + i + "] <object #" + output.objectNumber(mem[i]) + ">"); if(mem[i] != null) mem[i].dumpStatus(output);
            }
        }
    }


    private void dumpMemory(DataOutput output, Memory[] mem) throws IOException
    {
        long len;
        byte[] temp = new byte[0];
        if (mem == null)
            output.writeInt(0);
        else
        {
            output.writeInt(mem.length);
            for (int i = 0; i< mem.length; i++)
            {
                if (mem[i] == null)
                    output.writeLong(-1);
                else
                {
                    len = mem[i].getSize();
                    if (temp.length < (int) len)
                        temp = new byte[(int) len];
                    if (mem[i].isAllocated())
                    {
                        try
                        {
                            mem[i].copyContentsInto(0, temp, 0, (int) len);
                        }
                        catch (IllegalStateException e)
                        {
                            len = 0;
                        }
                        output.writeLong(len);
                        if (len > 0 )
                            output.write(temp);
                    }
                    else
                    {
                        output.writeLong(0);
                    }
                }
            }
        }
    }

    private void loadMemory(DataInput input, Memory[] mem, int size) throws IOException
    {
        long len;
        byte[] temp;
        for (int i = 0; i< size; i++)
        {
            len = input.readLong();
            if (len >= 0)
            {
                System.out.println(len);
                temp = new byte[(int) len];
                input.readFully(temp, 0, (int) len);
                //if (mem[i] == null)
                    //                    mem[i] = new ;
                mem[i].copyContentsFrom(0, temp, 0, (int) len);
            }
            else
                mem[i] = null;
        }
    }

    private Memory[] getReadIndex()
    {
        if (isSupervisor)
            return (readIndex = readSupervisorIndex = new Memory[INDEX_SIZE]);
        else
            return (readIndex = readUserIndex = new Memory[INDEX_SIZE]);
    }

    private Memory[] getWriteIndex()
    {
        if (isSupervisor)
            return (writeIndex = writeSupervisorIndex = new Memory[INDEX_SIZE]);
        else
            return (writeIndex = writeUserIndex = new Memory[INDEX_SIZE]);
    }

    private void setReadIndexValue(int index, Memory value)
    {
        try {
            readIndex[index] = value;
        } catch (NullPointerException e) {
            getReadIndex()[index] = value;
        }
    }

    private Memory getReadIndexValue(int index)
    {
        try {
            return readIndex[index];
        } catch (NullPointerException e) {
            return getReadIndex()[index];
        }
    }

    private void setWriteIndexValue(int index, Memory value)
    {
        try {
            writeIndex[index] = value;
        } catch (NullPointerException e) {
            getWriteIndex()[index] = value;
        }
    }

    private Memory getWriteIndexValue(int index)
    {
        try {
            return writeIndex[index];
        } catch (NullPointerException e) {
            return getWriteIndex()[index];
        }
    }

    public int getLastWalkedAddress()
    {
        return lastAddress;
    }

    public boolean isSupervisor()
    {
        return isSupervisor;
    }

    public void setSupervisor(boolean value)
    {
        isSupervisor = value;
        if (isSupervisor)
        {
            readIndex = readSupervisorIndex;
            writeIndex = writeSupervisorIndex;
        }
        else
        {
           readIndex = readUserIndex;
           writeIndex = writeUserIndex;
        }
    }

    public boolean isPagingEnabled()
    {
        return !pagingDisabled;
    }

    public void setPagingEnabled(boolean value)
    {
        if (value) {
            if (!((PhysicalAddressSpace)target).getGateA20State())
                System.err.println("PAGING with GateA20 Masked!!!");
        }
        pagingDisabled = !value;
        flush();
    }

    public void setPageCacheEnabled(boolean value)
    {
        pageCacheEnabled = value;
    }

    public void setPageSizeExtensionsEnabled(boolean value)
    {
        pageSizeExtensions = value;
        flush();
    }

    public void setPageWriteThroughEnabled(boolean value)
    {
        //System.err.println("ERR: Write Through Caching enabled for TLBs");
    }

    public void setGlobalPagesEnabled(boolean value)
    {
        if (globalPagesEnabled == value)
            return;

        globalPagesEnabled = value;
        flush();
    }

    public void setWriteProtectUserPages(boolean value)
    {
        if (value) {
            for (int i=0; i<INDEX_SIZE; i++)
                nullIndex(writeSupervisorIndex, i);
        }

        writeProtectUserPages = value;
    }


    public boolean pagingDisabled()
    {
        return pagingDisabled;
    }

    public void flush()
    {
        for (int i=0; i<INDEX_SIZE; i++)
        {
            pageFlags[i] = FOUR_K;
        }
        nonGlobalPages.clear();

        readUserIndex = null;
        writeUserIndex = null;
        readSupervisorIndex = null;
        writeSupervisorIndex = null;
    }

    private void partialFlush()
    {
        if (!globalPagesEnabled)
        {
            flush();
            return;
        }

        Enumeration<Integer> ee = nonGlobalPages.keys();
        while (ee.hasMoreElements())
        {
            int index = ee.nextElement().intValue();
            nullIndex(readSupervisorIndex, index);
            nullIndex(writeSupervisorIndex, index);
            nullIndex(readUserIndex, index);
            nullIndex(writeUserIndex, index);
            pageFlags[index] = FOUR_K;
        }
        nonGlobalPages.clear();

        /*
          for (int i=0; i<pageFlags.length; i++)
          {
          if ((pageFlags[i] & IS_GLOBAL_MASK) != 0)
          continue;

          readSupervisorIndex[i] = null;
          writeSupervisorIndex[i] = null;
          readUserIndex[i] = null;
          writeUserIndex[i] = null;
          }
        */
    }

    private void nullIndex(Memory[] array, int index)
    {
        try {
            array[index] = null;
        } catch (NullPointerException e) {}
    }

    public void setPageDirectoryBaseAddress(int address)
    {
        baseAddress = address & 0xFFFFF000;
        partialFlush();
    }

    public void invalidateTLBEntry(int offset)
    {
        int index = offset >>> INDEX_SHIFT;
        if ((pageFlags[index] & IS_4_M_MASK) == 0)
        {
            nullIndex(readSupervisorIndex, index);
            nullIndex(writeSupervisorIndex, index);
            nullIndex(readUserIndex, index);
            nullIndex(writeUserIndex, index);
            nonGlobalPages.remove(new Integer(index));
        }
        else
        {
            index = ((offset & 0xFFC00000) >>> 12);
            for (int i=0; i<1024; i++, index++)
            {
                nullIndex(readSupervisorIndex, index);
                nullIndex(writeSupervisorIndex, index);
                nullIndex(readUserIndex, index);
                nullIndex(writeUserIndex, index);
                nonGlobalPages.remove(new Integer(index));
            }
        }
    }

    public Memory validateTLBEntryRead(int offset)
    {
        int idx = offset >>> INDEX_SHIFT;
        if (pagingDisabled)
        {
            setReadIndexValue(idx, target.getReadMemoryBlockAt(offset));
            return readIndex[idx];
        }

        lastAddress = offset;

        int directoryAddress = baseAddress | (0xFFC & (offset >>> 20)); // This should be (offset >>> 22) << 2.
        int directoryRawBits = target.getDoubleWord(directoryAddress);

        boolean directoryPresent = (0x1 & directoryRawBits) != 0;
        if (!directoryPresent)
        {
            if (isSupervisor)
                return PF_NOT_PRESENT_RS;
            else
                return PF_NOT_PRESENT_RU;
        }

        boolean directoryGlobal = globalPagesEnabled && ((0x100 & directoryRawBits) != 0);
        boolean directoryReadWrite = (0x2 & directoryRawBits) != 0;
        boolean directoryUser = (0x4 & directoryRawBits) != 0;
        boolean directoryIs4MegPage = ((0x80 & directoryRawBits) != 0) && pageSizeExtensions;

        if (directoryIs4MegPage)
        {
            if (!directoryUser)
            {
                if (!isSupervisor)
                    return PF_PROTECTION_VIOLATION_RU;
            }

            if ((directoryRawBits & 0x20) == 0)
            {
                directoryRawBits |= 0x20;
                target.setDoubleWord(directoryAddress, directoryRawBits);
            }

            int fourMegPageStartAddress = 0xFFC00000 & directoryRawBits;
            byte flag = FOUR_M;
            if (directoryGlobal)
                flag = FOUR_M_GLOBAL;

            if (!pageCacheEnabled)
                return target.getReadMemoryBlockAt(fourMegPageStartAddress | (offset & 0x3FFFFF));

            int tableIndex = (0xFFC00000 & offset) >>> 12;
            for (int i=0; i<1024; i++)
            {
                Memory m = target.getReadMemoryBlockAt(fourMegPageStartAddress);
                fourMegPageStartAddress += BLOCK_SIZE;
                pageFlags[tableIndex] = flag;
                setReadIndexValue(tableIndex++, m);
                if (directoryGlobal)
                    continue;

                Integer iidx = new Integer(i);
                nonGlobalPages.put(iidx, iidx);
            }

            return readIndex[idx];
        }
        else
        {
            int directoryBaseAddress = directoryRawBits & 0xFFFFF000;
            boolean directoryPageLevelWriteThrough = (0x8 & directoryRawBits) != 0;
            boolean directoryPageCacheDisable = (0x10 & directoryRawBits) != 0;
            boolean directoryDirty = (0x40 & directoryRawBits) != 0;

            int tableAddress = directoryBaseAddress | ((offset >>> 10) & 0xFFC);
            int tableRawBits = target.getDoubleWord(tableAddress);

            boolean tablePresent = (0x1 & tableRawBits) != 0;
            if (!tablePresent)
            {
                if (isSupervisor)
                    return PF_NOT_PRESENT_RS;
                else
                    return PF_NOT_PRESENT_RU;
            }

            boolean tableGlobal = globalPagesEnabled && ((0x100 & tableRawBits) != 0);
            boolean tableReadWrite = (0x2 & tableRawBits) != 0;
            boolean tableUser = (0x4 & tableRawBits) != 0;

            boolean pageIsUser = tableUser && directoryUser;
            boolean pageIsReadWrite = tableReadWrite || directoryReadWrite;
            if (pageIsUser)
                pageIsReadWrite = tableReadWrite && directoryReadWrite;

            if (!pageIsUser)
            {
                if (!isSupervisor)
                    return PF_PROTECTION_VIOLATION_RU;
            }

            if ((tableRawBits & 0x20) == 0)
            {
                tableRawBits |= 0x20;
                target.setDoubleWord(tableAddress, tableRawBits);
            }

            int fourKStartAddress = tableRawBits & 0xFFFFF000;
            if (!pageCacheEnabled)
                return target.getReadMemoryBlockAt(fourKStartAddress);

            if (tableGlobal)
                pageFlags[idx] = FOUR_K_GLOBAL;
            else
            {
                pageFlags[idx] = FOUR_K;

                Integer iidx = new Integer(idx);
                nonGlobalPages.put(iidx, iidx);
            }

            setReadIndexValue(idx, target.getReadMemoryBlockAt(fourKStartAddress));
            return readIndex[idx];
        }
    }

    public Memory validateTLBEntryWrite(int offset)
    {
        int idx = offset >>> INDEX_SHIFT;
        if (pagingDisabled)
        {
            setWriteIndexValue(idx, target.getWriteMemoryBlockAt(offset));
            return writeIndex[idx];
        }

        lastAddress = offset;

        int directoryAddress = baseAddress | (0xFFC & (offset >>> 20)); // This should be (offset >>> 22) << 2.
        int directoryRawBits = target.getDoubleWord(directoryAddress);

        boolean directoryPresent = (0x1 & directoryRawBits) != 0;
        if (!directoryPresent)
        {
            if (isSupervisor)
                return PF_NOT_PRESENT_WS;
            else
                return PF_NOT_PRESENT_WU;
        }

        boolean directoryGlobal = globalPagesEnabled && ((0x100 & directoryRawBits) != 0);
        boolean directoryReadWrite = (0x2 & directoryRawBits) != 0;
        boolean directoryUser = (0x4 & directoryRawBits) != 0;
        boolean directoryIs4MegPage = ((0x80 & directoryRawBits) != 0) && pageSizeExtensions;

        if (directoryIs4MegPage)
        {
            if (directoryUser)
            {
                if (!directoryReadWrite) // if readWrite then all access is OK
                {
                    if (isSupervisor)
                    {
                        if (writeProtectUserPages)
                            return PF_PROTECTION_VIOLATION_WS;
                    }
                    else
                        return PF_PROTECTION_VIOLATION_WU;
                }
            }
            else // A supervisor page
            {
                if (directoryReadWrite)
                {
                    if (!isSupervisor)
                        return PF_PROTECTION_VIOLATION_WU;
                }
                else
                {
                    if (isSupervisor)
                        return PF_PROTECTION_VIOLATION_WS;
                    else
                        return PF_PROTECTION_VIOLATION_WU;
                }
            }

            if ((directoryRawBits & 0x60) != 0x60)
            {
                directoryRawBits |= 0x60;
                target.setDoubleWord(directoryAddress, directoryRawBits);
            }

            int fourMegPageStartAddress = 0xFFC00000 & directoryRawBits;
            byte flag = FOUR_M;
            if (directoryGlobal)
                flag = FOUR_M_GLOBAL;

            if (!pageCacheEnabled)
                return target.getWriteMemoryBlockAt(fourMegPageStartAddress | (offset & 0x3FFFFF));

            int tableIndex = (0xFFC00000 & offset) >>> 12;
            for (int i=0; i<1024; i++)
            {
                Memory m = target.getWriteMemoryBlockAt(fourMegPageStartAddress);
                fourMegPageStartAddress += BLOCK_SIZE;
                pageFlags[tableIndex] = flag;
                setWriteIndexValue(tableIndex++, m);

                if (directoryGlobal)
                    continue;

                Integer iidx = new Integer(i);
                nonGlobalPages.put(iidx, iidx);
            }

            return writeIndex[idx];
        }
        else
        {
            int directoryBaseAddress = directoryRawBits & 0xFFFFF000;
            boolean directoryPageLevelWriteThrough = (0x8 & directoryRawBits) != 0;
            boolean directoryPageCacheDisable = (0x10 & directoryRawBits) != 0;
            boolean directoryDirty = (0x40 & directoryRawBits) != 0;

            int tableAddress = directoryBaseAddress | ((offset >>> 10) & 0xFFC);
            int tableRawBits = target.getDoubleWord(tableAddress);

            boolean tablePresent = (0x1 & tableRawBits) != 0;
            if (!tablePresent)
            {
                if (isSupervisor)
                    return PF_NOT_PRESENT_WS;
                else
                    return PF_NOT_PRESENT_WU;
            }

            boolean tableGlobal = globalPagesEnabled && ((0x100 & tableRawBits) != 0);
            boolean tableReadWrite = (0x2 & tableRawBits) != 0;
            boolean tableUser = (0x4 & tableRawBits) != 0;

            boolean pageIsUser = tableUser && directoryUser;
            boolean pageIsReadWrite = tableReadWrite || directoryReadWrite;
            if (pageIsUser)
                pageIsReadWrite = tableReadWrite && directoryReadWrite;

            if (pageIsUser)
            {
                if (!pageIsReadWrite) // if readWrite then all access is OK
                {
                    if (isSupervisor)
                    {
                        if (writeProtectUserPages)
                            return PF_PROTECTION_VIOLATION_WS;
                    }
                    else
                        return PF_PROTECTION_VIOLATION_WU;
                }
            }
            else // A supervisor page
            {
                if (pageIsReadWrite)
                {
                    if (!isSupervisor)
                        return PF_PROTECTION_VIOLATION_WU;
                }
                else
                {
                    if (isSupervisor)
                        return PF_PROTECTION_VIOLATION_WS;
                    else
                        return PF_PROTECTION_VIOLATION_WU;
                }
            }

            if ((tableRawBits & 0x60) != 0x60)
            {
                tableRawBits |= 0x60;
                target.setDoubleWord(tableAddress, tableRawBits);
            }

            int fourKStartAddress = tableRawBits & 0xFFFFF000;
            if (!pageCacheEnabled)
                return target.getWriteMemoryBlockAt(fourKStartAddress);

            if (tableGlobal)
                pageFlags[idx] = FOUR_K_GLOBAL;
            else
            {
                pageFlags[idx] = FOUR_K;

                Integer iidx = new Integer(idx);
                nonGlobalPages.put(iidx, iidx);
            }

            setWriteIndexValue(idx, target.getWriteMemoryBlockAt(fourKStartAddress));
            return writeIndex[idx];
        }
    }

    public Memory getReadMemoryBlockAt(int offset)
    {
        return getReadIndexValue(offset >>> INDEX_SHIFT);
    }

    public Memory getWriteMemoryBlockAt(int offset)
    {
        return getWriteIndexValue(offset >>> INDEX_SHIFT);
    }

    void replaceBlocks(Memory oldBlock, Memory newBlock)
    {
        try {
            for (int i = 0; i < INDEX_SIZE; i++)
                if (readUserIndex[i] == oldBlock)
                    readUserIndex[i] = newBlock;
        } catch (NullPointerException e) {}

        try {
            for (int i = 0; i < INDEX_SIZE; i++)
                if (writeUserIndex[i] == oldBlock)
                    writeUserIndex[i] = newBlock;
        } catch (NullPointerException e) {}

        try {
            for (int i = 0; i < INDEX_SIZE; i++)
                if (readSupervisorIndex[i] == oldBlock)
                    readSupervisorIndex[i] = newBlock;
        } catch (NullPointerException e) {}

        try {
            for (int i = 0; i < INDEX_SIZE; i++)
                if (writeSupervisorIndex[i] == oldBlock)
                    writeSupervisorIndex[i] = newBlock;
        } catch (NullPointerException e) {}
    }

    public byte getByte(int offset)
    {
        try
        {
            return super.getByte(offset);
        }
        catch (NullPointerException e) {}
        catch (ProcessorException p) {}

        return validateTLBEntryRead(offset).getByte(offset & BLOCK_MASK);
    }

    public short getWord(int offset)
    {
        try
        {
            return super.getWord(offset);
        }
        catch (NullPointerException e) {}
        catch (ProcessorException p) {}

        Memory m = validateTLBEntryRead(offset);
        try
        {
            return m.getWord(offset & BLOCK_MASK);
        }
        catch (ArrayIndexOutOfBoundsException e)
        {
            return getWordInBytes(offset);
        }
    }

    public int getDoubleWord(int offset)
    {
        try
        {
            return super.getDoubleWord(offset);
        }
        catch (NullPointerException e) {}
        catch (ProcessorException p) {}

        Memory m = validateTLBEntryRead(offset);
        try
        {
            return m.getDoubleWord(offset & BLOCK_MASK);
        }
        catch (ArrayIndexOutOfBoundsException e)
        {
            return getDoubleWordInBytes(offset);
        }
    }

    public void setByte(int offset, byte data)
    {
        try
        {
            super.setByte(offset, data);
            return;
        }
        catch (NullPointerException e) {}
        catch (ProcessorException p) {}

        validateTLBEntryWrite(offset).setByte(offset & BLOCK_MASK, data);
    }

    public void setWord(int offset, short data)
    {
        try
        {
            super.setWord(offset, data);
            return;
        }
        catch (NullPointerException e) {}
        catch (ProcessorException p) {}

        Memory m = validateTLBEntryWrite(offset);
        try
        {
            m.setWord(offset & BLOCK_MASK, data);
        }
        catch (ArrayIndexOutOfBoundsException e)
        {
            setWordInBytes(offset, data);
        }
    }

    public void setDoubleWord(int offset, int data)
    {
        try
        {
            super.setDoubleWord(offset, data);
            return;
        }
        catch (NullPointerException e) {}
        catch (ProcessorException p) {}

        Memory m = validateTLBEntryWrite(offset);
        try
        {
            m.setDoubleWord(offset & BLOCK_MASK, data);
        }
        catch (ArrayIndexOutOfBoundsException e)
        {
            setDoubleWordInBytes(offset, data);
        }
    }

    public void clear()
    {
        target.clear();
    }

    public int execute(Processor cpu, int offset)
    {
        Memory memory = getReadMemoryBlockAt(offset);

        try {
            return memory.execute(cpu, offset & AddressSpace.BLOCK_MASK);
        } catch (NullPointerException n) {
            memory = validateTLBEntryRead(offset); //memory object was null (needs mapping)
        } catch (ProcessorException p) {
            memory = validateTLBEntryRead(offset); //memory object caused a page fault (double check)
        }

        try {
            return memory.execute(cpu, offset & AddressSpace.BLOCK_MASK);
        } catch (ProcessorException p) {
            cpu.handleProtectedModeException(p.getVector(), p.hasErrorCode(), p.getErrorCode());
            return 1;
        }
    }

    public CodeBlock decodeCodeBlockAt(Processor cpu, int offset)
    {
        Memory memory = getReadMemoryBlockAt(offset);


        try {
            return memory.decodeCodeBlockAt(cpu, offset & AddressSpace.BLOCK_MASK);
        } catch (NullPointerException n) {
            memory = validateTLBEntryRead(offset); //memory object was null (needs mapping)
        } catch (ProcessorException p) {
            memory = validateTLBEntryRead(offset); //memory object caused a page fault (double check)
        }

        CodeBlock block= memory.decodeCodeBlockAt(cpu, offset & AddressSpace.BLOCK_MASK);
        return block;
    }

    public static final class PageFaultWrapper extends Memory
    {
        private ProcessorException pageFault;

        public void dumpSR(org.jpc.support.SRDumper output) throws IOException
        {
            if(output.dumped(this))
                return;
            dumpSRPartial(output);
            output.endObject();
        }

        public void dumpSRPartial(org.jpc.support.SRDumper output) throws IOException
        {
            super.dumpSRPartial(output);
            output.dumpObject(pageFault);
        }

        public PageFaultWrapper(org.jpc.support.SRLoader input) throws IOException
        {
            super(input);
            pageFault = (ProcessorException)(input.loadObject());
        }

        public static org.jpc.SRDumpable loadSR(org.jpc.support.SRLoader input) throws IOException
        {
            org.jpc.SRDumpable x = new PageFaultWrapper(input);
            input.endObject();
            return x;
        }

        public void dumpStatusPartial(org.jpc.support.StatusDumper output)
        {
            super.dumpStatusPartial(output);
            output.println("\tpageFault <object #" + output.objectNumber(pageFault) + ">"); pageFault.dumpStatus(output);
        }

         public void dumpStatus(org.jpc.support.StatusDumper output)
         {
             if(output.dumped(this))
                 return;

             output.println("#" + output.objectNumber(this) + ": PageFaultWrapper:");
             dumpStatusPartial(output);
             output.endObject();
        }

        public PageFaultWrapper(int errorCode)
        {
            pageFault = new ProcessorException(Processor.PROC_EXCEPTION_PF, errorCode, true);
        }

        private final void fill()
        {
            //pageFault.fillInStackTrace();
        }

        public ProcessorException getFault()
        {
            return pageFault;
        }

        public void clear() {}

        public void clear(int start, int length) {}

        public void copyContentsInto(int address, byte[] buffer, int off, int len)
        {
            fill();
            throw pageFault;
        }

        public void copyContentsFrom(int address, byte[] buffer, int off, int len)
        {
            fill();
            throw pageFault;
        }

        public long getSize()
        {
            return 0;
        }

        public byte getByte(int offset)
        {
            fill();
            throw pageFault;
        }

        public short getWord(int offset)
        {
            fill();
            throw pageFault;
        }

        public int getDoubleWord(int offset)
        {
            fill();
            throw pageFault;
        }

        public long getQuadWord(int offset)
        {
            fill();
            throw pageFault;
        }

        public long getLowerDoubleQuadWord(int offset)
        {
            fill();
            throw pageFault;
        }

        public long getUpperDoubleQuadWord(int offset)
        {
            fill();
            throw pageFault;
        }

        public void setByte(int offset, byte data)
        {
            fill();
            throw pageFault;
        }

        public void setWord(int offset, short data)
        {
            fill();
            throw pageFault;
        }

        public void setDoubleWord(int offset, int data)
        {
            fill();
            throw pageFault;
        }

        public void setQuadWord(int offset, long data)
        {
            fill();
            throw pageFault;
        }

        public void setLowerDoubleQuadWord(int offset, long data)
        {
            fill();
            throw pageFault;
        }

        public void setUpperDoubleQuadWord(int offset, long data)
        {
            fill();
            throw pageFault;
        }

        public int execute(Processor cpu, int offset)
        {
            fill();
            throw pageFault;
        }

        public CodeBlock decodeCodeBlockAt(Processor cpu, int offset)
        {
            fill();
            throw pageFault;
        }
    }

    public void reset()
    {
        flush();

        baseAddress = 0;
        lastAddress = 0;
        pagingDisabled = true;
        globalPagesEnabled = false;
        writeProtectUserPages = false;
        pageSizeExtensions = false;

        readUserIndex = null;
        writeUserIndex = null;
        readSupervisorIndex = null;
        writeSupervisorIndex = null;
    }

    public boolean updated()
    {
        return target.updated();
    }

    public void updateComponent(HardwareComponent component)
    { }

    public boolean initialised()
    {
        return (target != null);
    }

    public void acceptComponent(HardwareComponent component)
    {
        if (component instanceof PhysicalAddressSpace)
            target = (AddressSpace) component;
    }

    public void timerCallback() {}

    public String toString()
    {
        return "Linear Address Space";
    }
}

