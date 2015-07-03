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

package org.jpc.emulator.memory;

import java.io.*;
import java.util.*;

import org.jpc.emulator.*;
import org.jpc.emulator.memory.codeblock.CodeBlockManager;
import org.jpc.emulator.processor.Processor;

/**
 * Class that emulates the 32bit physical address space of the machine.  Mappings
 * between address and blocks are performed either using a single stage lookup on
 * the RAM area for speed, or a two stage lookup for the rest of the address space
 * for space-efficiency.
 * <p>
 * All addresses are initially mapped to an inner class instance that returns
 * <code>-1</code> on all reads (as all data lines float high).
 * @author Chris Dennis
 */
public final class PhysicalAddressSpace extends AddressSpace implements HardwareComponent {
    private static final int GATEA20_MASK = 0xffefffff;
    private static final int GATEA20_PAGEMASK = GATEA20_MASK >>> INDEX_SHIFT;
    private static final int GATEA20_PAGEOFFSET = (~GATEA20_MASK) >>> INDEX_SHIFT;
    private int sysRAMSize;
    private int quickIndexSize;
    private static final int TOP_INDEX_BITS = (32 - INDEX_SHIFT) / 2;
    private static final int BOTTOM_INDEX_BITS = 32 - INDEX_SHIFT - TOP_INDEX_BITS;
    private static final int TOP_INDEX_SHIFT = 32 - TOP_INDEX_BITS;
    private static final int TOP_INDEX_SIZE = 1 << TOP_INDEX_BITS;
    private static final int BOTTOM_INDEX_SHIFT = 32 - TOP_INDEX_BITS - BOTTOM_INDEX_BITS;
    private static final int BOTTOM_INDEX_SIZE = 1 << BOTTOM_INDEX_BITS;
    private static final int BOTTOM_INDEX_MASK = BOTTOM_INDEX_SIZE - 1;
    private static final Memory UNCONNECTED = new UnconnectedMemoryBlock();
    private boolean gateA20MaskState;
    private Memory[] quickNonA20MaskedIndex,  quickA20MaskedIndex,  quickIndex;
    private Memory[][] nonA20MaskedIndex,  a20MaskedIndex,  index;
    private LinearAddressSpace linearAddr;
    private CodeBlockManager manager = null;

    public void setPage0Hack(TraceTrap tt)
    {
        Memory page0 = quickNonA20MaskedIndex[0];
        if(page0 instanceof LazyCodeBlockMemory)
            ((LazyCodeBlockMemory)page0).setPage0Hack(tt);
    }

    /**
     * Constructs an address space which is initially empty.  All addresses are
     * mapped to an instance of the inner class <code>UnconnectedMemoryBlock</code>
     * whose data lines float high.
     */
    public PhysicalAddressSpace(CodeBlockManager manager, int ramSize) {
        this.manager = manager;
        sysRAMSize = ramSize;
        quickIndexSize = ramSize >>> INDEX_SHIFT;
        quickNonA20MaskedIndex = new Memory[quickIndexSize];
        clearArray(quickNonA20MaskedIndex, UNCONNECTED);
        quickA20MaskedIndex = new Memory[quickIndexSize];
        clearArray(quickA20MaskedIndex, UNCONNECTED);

        nonA20MaskedIndex = new Memory[TOP_INDEX_SIZE][];
        a20MaskedIndex = new Memory[TOP_INDEX_SIZE][];

        initialiseMemory();
        setGateA20State(false);
    }

    public void dumpStatusPartial(StatusDumper output)
    {
        super.dumpStatusPartial(output);
        output.println("\tsysRAMSize " + sysRAMSize + " quickIndexSize " + quickIndexSize);
        output.println("\tgateA20MaskState " + gateA20MaskState);

        dumpMemoryTableStatus(output, quickNonA20MaskedIndex, "quickNonA20MaskedIndex");
        dumpMemoryTableStatus(output, quickA20MaskedIndex, "quickA20MaskedIndex");
        dumpMemoryTableStatus(output, quickIndex, "quickIndex");
        dumpMemoryDTableStatus(output, nonA20MaskedIndex, "nonA20MaskedIndex");
        dumpMemoryDTableStatus(output, a20MaskedIndex, "a20MaskedIndex");
        dumpMemoryDTableStatus(output, index, "index");
        output.println("\tUNCONNECTED <object #" + output.objectNumber(UNCONNECTED) + ">"); if(UNCONNECTED != null) UNCONNECTED.dumpStatus(output);
        output.println("\tlinearAddr <object #" + output.objectNumber(linearAddr) + ">"); if(linearAddr != null) linearAddr.dumpStatus(output);
    }

    public void dumpStatus(StatusDumper output)
    {
        if(output.dumped(this))
            return;

        output.println("#" + output.objectNumber(this) + ": PhysicalAddressSpace:");
        dumpStatusPartial(output);
        output.endObject();
    }

    public int findFirstRAMPage(int pageNoLowBound)
    {
        try {
            while(true) {
                if(quickNonA20MaskedIndex[pageNoLowBound].getClass() == LazyCodeBlockMemory.class)
                    return pageNoLowBound;
                pageNoLowBound++;
            }
        } catch(ArrayIndexOutOfBoundsException e) {
            return -1; //No more RAM pages.
        }
    }

    public void readRAMPage(int pageNo, byte[] buffer4096Bytes)
    {
        readRAMPage(pageNo, buffer4096Bytes, 0);
    }

    public void readRAMPage(int pageNo, byte[] buffer4096Bytes, int off)
    {
        if(pageNo >= quickNonA20MaskedIndex.length) {
            Arrays.fill(buffer4096Bytes, off, off + 4096, (byte)0);
            return;
        }
        if(!(quickNonA20MaskedIndex[pageNo].getClass() == LazyCodeBlockMemory.class)) {
            Arrays.fill(buffer4096Bytes, off, off + 4096, (byte)0);
            return;
        }
        LazyCodeBlockMemory ramPage = (LazyCodeBlockMemory)quickNonA20MaskedIndex[pageNo];

        if(!ramPage.isDirty()) {
            Arrays.fill(buffer4096Bytes, off, off + 4096, (byte)0);
            return;
        }

        ramPage.copyContentsIntoArray(0, buffer4096Bytes, off, 4096);
    }


    private void reconstructA20MaskedTables()
    {
        a20MaskedIndex = new Memory[TOP_INDEX_SIZE][];
        quickA20MaskedIndex = new Memory[quickIndexSize];
        clearArray(quickA20MaskedIndex, UNCONNECTED);
        for(int i = 0; i < quickIndexSize; i++)
            quickA20MaskedIndex[i] = quickNonA20MaskedIndex[i & GATEA20_PAGEMASK];
        for(int i = 0; i < TOP_INDEX_SIZE; i++) {
            if(nonA20MaskedIndex[i] == null)
                continue;
            for(int j = 0; j < BOTTOM_INDEX_SIZE; j++) {
                if(nonA20MaskedIndex[i][j] == null)
                    continue;
                int pageNo1 = i * BOTTOM_INDEX_SIZE + j;
                int pageNo2 = i * BOTTOM_INDEX_SIZE + j + GATEA20_PAGEOFFSET;
                Memory[] group1;
                Memory[] group2;
                if((pageNo1 & GATEA20_PAGEMASK) != pageNo1)
                    continue;

                if((group1 = a20MaskedIndex[pageNo1 >>> BOTTOM_INDEX_BITS]) == null)
                    group1 = a20MaskedIndex[pageNo1 >>> BOTTOM_INDEX_BITS] = new Memory[BOTTOM_INDEX_SIZE];
                if((group2 = a20MaskedIndex[pageNo2 >>> BOTTOM_INDEX_BITS]) == null)
                    group2 = a20MaskedIndex[pageNo2 >>> BOTTOM_INDEX_BITS] = new Memory[BOTTOM_INDEX_SIZE];

                group1[pageNo1 & BOTTOM_INDEX_MASK] = nonA20MaskedIndex[i][j];
                group2[pageNo2 & BOTTOM_INDEX_MASK] = nonA20MaskedIndex[i][j];
            }
        }
    }

    public void dumpSRPartial(SRDumper output) throws IOException
    {
        super.dumpSRPartial(output);
        output.specialObject(UNCONNECTED);
        output.dumpInt(sysRAMSize);
        output.dumpInt(quickIndexSize);
        output.dumpBoolean(gateA20MaskState);
        dumpMemoryTableSR(output, quickNonA20MaskedIndex);
        dumpMemoryDTableSR(output, nonA20MaskedIndex);
        output.dumpObject(linearAddr);
        output.dumpObject(manager);
    }

    private Memory[][] loadMemoryDTableSR(SRLoader input) throws IOException
    {
        boolean dTablePresent = input.loadBoolean();
        if(!dTablePresent)
            return null;

        Memory[][] mem = new Memory[input.loadInt()][];
        for(int i = 0; i < mem.length; i++)
            mem[i] = loadMemoryTableSR(input);
        return mem;
    }

    private Memory[] loadMemoryTableSR(SRLoader input) throws IOException
    {
        boolean dTablePresent = input.loadBoolean();
        if(!dTablePresent)
            return null;

        Memory[] mem = new Memory[input.loadInt()];
        for(int i = 0; i < mem.length; i++)
            mem[i] = (Memory)(input.loadObject());
        return mem;
    }

    public PhysicalAddressSpace(SRLoader input) throws IOException
    {
        super(input);
        input.specialObject(UNCONNECTED);
        sysRAMSize = input.loadInt();
        quickIndexSize = input.loadInt();
        gateA20MaskState = input.loadBoolean();
        quickNonA20MaskedIndex = loadMemoryTableSR(input);
        nonA20MaskedIndex = loadMemoryDTableSR(input);
        reconstructA20MaskedTables();
        if(gateA20MaskState) {
            quickIndex = quickNonA20MaskedIndex;
            index = nonA20MaskedIndex;
        } else {
            quickIndex = quickA20MaskedIndex;
            index = a20MaskedIndex;
        }

        linearAddr = (LinearAddressSpace)(input.loadObject());
        manager = (CodeBlockManager)input.loadObject();
    }

    private void dumpMemoryDTableSR(SRDumper output, Memory[][] mem) throws IOException
    {
        if(mem == null) {
            output.dumpBoolean(false);
        } else {
            output.dumpBoolean(true);
            output.dumpInt(mem.length);
            for(int i = 0; i < mem.length; i++)
                dumpMemoryTableSR(output, mem[i]);
        }
    }

    private void dumpMemoryTableSR(SRDumper output, Memory[] mem) throws IOException
    {
        if(mem == null) {
            output.dumpBoolean(false);
        } else {
            output.dumpBoolean(true);
            output.dumpInt(mem.length);
            for(int i = 0; i < mem.length; i++)
                output.dumpObject(mem[i]);
        }
    }

    private void dumpMemoryTableStatus(StatusDumper output, Memory[] mem, String name)
    {
        if(mem == null) {
            output.println("\t" + name +" null");
        } else {
            for(int i = 0; i < mem.length; i++) {
                output.println("\t" + name + "[" + i + "] <object #" + output.objectNumber(mem[i]) + ">"); if(mem[i] != null) mem[i].dumpStatus(output);
            }
        }
    }

    private void dumpMemoryDTableStatus(StatusDumper output, Memory[][] mem, String name)
    {
        if(mem == null) {
            output.println("\t" + name +": null");
        } else {
            for(int i = 0; i < mem.length; i++)
                if(mem[i] != null)
                    for(int j = 0; j < mem.length; j++) {
                        output.println("\t" + name + "[" + i + "][" + j + "] <object #" + output.objectNumber(mem[i][j]) + ">"); if(mem[i][j] != null) mem[i][j].dumpStatus(output);
                    }
                else
                        output.println("\t" + name + "[" + i + "] null");
        }
    }

    private void initialiseMemory()
    {
        for(int i = 0; i < sysRAMSize; i += AddressSpace.BLOCK_SIZE)
            mapMemory(i, new LazyCodeBlockMemory(AddressSpace.BLOCK_SIZE, manager));
        for(int i = 0; i < 32; i++)
            mapMemory(0xd0000 + i * AddressSpace.BLOCK_SIZE, new PhysicalAddressSpace.UnconnectedMemoryBlock());
    }

    public CodeBlockManager getCodeBlockManager()
    {
        return manager;
    }

    /**
     * Enables or disables the 20th address line.
     * <p>
     * If set to <code>true</code> then the 20th address line is enabled and memory
     * access works conventionally.  If set to <code>false</code> then the line
     * is held low, and therefore a memory wrapping effect emulating the behaviour
     * of and original 8086 is acheived.
     * @param value status of the A20 line.
     */
    public void setGateA20State(boolean value) {
        gateA20MaskState = value;
        if(value) {
            quickIndex = quickNonA20MaskedIndex;
            index = nonA20MaskedIndex;
        } else {
            quickIndex = quickA20MaskedIndex;
            index = a20MaskedIndex;
        }

        if((linearAddr != null) && linearAddr.isPagingEnabled())
            linearAddr.flush();
    }

    /**
     * Returns the status of the 20th address line.<p>
     * <p>
     * A <code>true</code> return indicates the 20th address line is enabled.  A
     * <code>false</code> return indicates that the 20th address line is held low
     * to emulate an 8086 memory system.
     * @return status of the A20 line.
     */
    public boolean getGateA20State()
    {
        return gateA20MaskState;
    }

    protected Memory getReadMemoryBlockAt(int offset)
    {
        return getMemoryBlockAt(offset);
    }

    protected Memory getWriteMemoryBlockAt(int offset)
    {
        return getMemoryBlockAt(offset);
    }

    public int executeReal(Processor cpu, int offset)
    {
        return getReadMemoryBlockAt(offset).executeReal(cpu, offset & AddressSpace.BLOCK_MASK);
    }

    public int executeProtected(Processor cpu, int offset)
    {
        System.err.println("Critical error: Trying to run Protected mode block in physical memory.");
        throw new IllegalStateException("Cannot execute protected mode block in physical memory");
    }

    public int executeVirtual8086(Processor cpu, int offset)
    {
        System.err.println("Critical error: Trying to run Protected mode block in physical memory.");
        throw new IllegalStateException("Cannot execute protected mode block in physical memory");
    }

    protected void replaceBlocks(Memory oldBlock, Memory newBlock) {
        for(int i = 0; i < quickA20MaskedIndex.length; i++)
            if(quickA20MaskedIndex[i] == oldBlock)
                quickA20MaskedIndex[i] = newBlock;
        for(int i = 0; i < quickNonA20MaskedIndex.length; i++)
            if(quickNonA20MaskedIndex[i] == oldBlock)
                quickNonA20MaskedIndex[i] = newBlock;
        for(Memory[] subArray : a20MaskedIndex) {
            if(subArray == null)
                continue;
            for(int j = 0; j < subArray.length; j++)
                if(subArray[j] == oldBlock)
                    subArray[j] = newBlock;
        }

        for(Memory[] subArray : nonA20MaskedIndex) {
            if(subArray == null)
                continue;
            for(int j = 0; j < subArray.length; j++)
                if(subArray[j] == oldBlock)
                    subArray[j] = newBlock;
        }
    }

    public static class MapWrapper implements Memory
    {
        private Memory memory;
        private int baseAddress;

        public void dumpSRPartial(SRDumper output) throws IOException
        {
            output.dumpObject(memory);
            output.dumpInt(baseAddress);
        }

        public void dumpStatusPartial(StatusDumper output)
        {
            //super.dumpStatusPartial(output); <no superclass 20090704>
            output.println("\tbaseAddress " + baseAddress);
            output.println("\tmemory <object #" + output.objectNumber(memory) + ">"); if(memory != null) memory.dumpStatus(output);
        }

        public void dumpStatus(StatusDumper output)
        {
            if(output.dumped(this))
                return;

            output.println("#" + output.objectNumber(this) + ": MapWrapper:");
            dumpStatusPartial(output);
            output.endObject();
        }

        public MapWrapper(SRLoader input) throws IOException
        {
            input.objectCreated(this);
            memory = (Memory)input.loadObject();
            baseAddress = input.loadInt();
        }

        MapWrapper(Memory mem, int base)
        {
            baseAddress = base;
            memory = mem;
        }

        public long getSize()
        {
            return BLOCK_SIZE;
        }

        public boolean isAllocated()
        {
            return memory.isAllocated();
        }

        public void clear()
        {
            memory.clear(baseAddress, (int) getSize());
        }

        public void clear(int start, int length)
        {
            if(start + length > getSize())
                throw new ArrayIndexOutOfBoundsException("Attempt to clear outside of memory bounds");
            start = baseAddress | start;
            memory.clear(start, length);
        }

        public void copyContentsIntoArray(int offset, byte[] buffer, int off, int len)
        {
            offset = baseAddress | offset;
            memory.copyContentsIntoArray(offset, buffer, off, len);
        }

        public void copyArrayIntoContents(int offset, byte[] buffer, int off, int len)
        {
            offset = baseAddress | offset;
            memory.copyArrayIntoContents(offset, buffer, off, len);
        }

        public byte getByte(int offset)
        {
            offset = baseAddress | offset;
            return memory.getByte(offset);
        }

        public short getWord(int offset)
        {
            offset = baseAddress | offset;
            return memory.getWord(offset);
        }

        public int getDoubleWord(int offset)
        {
            offset = baseAddress | offset;
            return memory.getDoubleWord(offset);
        }

        public long getQuadWord(int offset)
        {
            offset = baseAddress | offset;
            return memory.getQuadWord(offset);
        }

        public long getLowerDoubleQuadWord(int offset)
        {
            offset = baseAddress | offset;
            return memory.getQuadWord(offset);
        }

        public long getUpperDoubleQuadWord(int offset)
        {
            offset += 8;
            offset = baseAddress | offset;
            return memory.getQuadWord(offset);
        }

        public void setByte(int offset, byte data)
        {
            offset = baseAddress | offset;
            memory.setByte(offset, data);
        }

        public void setWord(int offset, short data)
        {
            offset = baseAddress | offset;
            memory.setWord(offset, data);
        }

        public void setDoubleWord(int offset, int data)
        {
            offset = baseAddress | offset;
            memory.setDoubleWord(offset, data);
        }

        public void setQuadWord(int offset, long data)
        {
            offset = baseAddress | offset;
            memory.setQuadWord(offset, data);
        }

        public void setLowerDoubleQuadWord(int offset, long data) {
            offset = baseAddress | offset;
            memory.setQuadWord(offset, data);
        }

        public void setUpperDoubleQuadWord(int offset, long data) {
            offset += 8;
            offset = baseAddress | offset;
            memory.setQuadWord(offset, data);
        }

        public int executeReal(Processor cpu, int offset) {
            offset = baseAddress | offset;
            return memory.executeReal(cpu, offset);
        }

        public int executeProtected(Processor cpu, int offset) {
            System.err.println("Critical error: Trying to run Protected mode block in physical memory.");
            throw new IllegalStateException("Cannot execute protected mode block in physical memory");
        }

        public int executeVirtual8086(Processor cpu, int offset) {
            System.err.println("Critical error: Trying to run Protected mode block in physical memory.");
            throw new IllegalStateException("Cannot execute protected mode block in physical memory");
        }

        public String toString() {
            return "Mapped Memory";
        }

        public void loadInitialContents(int address, byte[] buf, int off, int len)
        {
            throw new UnsupportedOperationException("Not supported yet.");
        }
    }

    public void clear()
    {
        for(Memory block : quickNonA20MaskedIndex)
            block.clear();
        for(Memory[] subArray : nonA20MaskedIndex) {
            if(subArray == null)
                continue;
            for(Memory block : subArray)
                try {
                    block.clear();
                } catch (NullPointerException e) {
                }
        }
    }

    /**
     * Clears all mapping in the given address range.
     * <p>
     * The corresponding blocks are pointed to an unconnected memory block whose
     * data lines all float high.  If the supplied range is not of <code>BLOCK_SIZE</code>
     * granularity then an <code>IllegalStateException</code> is thrown.
     * @param start inclusive lower bound
     * @param length number of addresses to clear
     * @throws java.lang.IllegalStateException if range is not of <code>BLOCK_SIZE</code> granularity
     */
    public void unmap(int start, int length)
    {
        if((start % BLOCK_SIZE) != 0) {
            System.err.println("Critical error: Illegal unmap request: start=" + Integer.toHexString(start) +
                ", length=" + Integer.toHexString(length) + ".");
            throw new IllegalStateException("Cannot deallocate memory starting at " + Integer.toHexString(start) +
                "; this is not block aligned at " + BLOCK_SIZE + " boundaries");
        }
        if((length % BLOCK_SIZE) != 0) {
            System.err.println("Critical error: Illegal unmap request: start=" + Integer.toHexString(start) +
                ", length=" + Integer.toHexString(length) + ".");
            throw new IllegalStateException("Cannot deallocate memory in partial blocks. " + length +
                " is not a multiple of " + BLOCK_SIZE);
        }
        for(int i = start; i < start + length; i += BLOCK_SIZE)
            setMemoryBlockAt(i, UNCONNECTED);
    }

    /**
     * Maps the given address range to the <code>underlying</code> object.
     * <p>
     * This will throw <code>IllegalStateException</code> if either the region is
     * not of <code>BLOCK_SIZE</code> granularity, or <code>underlying</code> is
     * not as long as the specified region.
     * @param underlying memory block to be mapped.
     * @param start inclusive start address.
     * @param length size of mapped region.
     * @throws java.lang.IllegalStateException if there is an error in the mapping.
     */
    public void mapMemoryRegion(Memory underlying, int start, int length)
    {
        if(underlying.getSize() < length) {
            System.err.println("Critical error: Map request size exceeds mapped memory size.");
            throw new IllegalStateException("Underlying memory (length=" + underlying.getSize() + ") is too short for mapping into region " + length + " bytes long");
        }
        if((start % BLOCK_SIZE) != 0) {
            System.err.println("Critical error: Illegal map request: start=" + Integer.toHexString(start) +
                ", length=" + Integer.toHexString(length) + ".");
            throw new IllegalStateException("Cannot map memory starting at " + Integer.toHexString(start) +
                "; this is not aligned to " + BLOCK_SIZE + " blocks");
        }
        if((length % BLOCK_SIZE) != 0) {
            System.err.println("Critical error: Illegal map request: start=" + Integer.toHexString(start) +
                ", length=" + Integer.toHexString(length) + ".");
            throw new IllegalStateException("Cannot map memory in partial blocks: " + length +
                " is not a multiple of " + BLOCK_SIZE);
        }
        unmap(start, length);

        long s = 0xFFFFFFFFl & start;
        for(long i = s; i < s + length; i += BLOCK_SIZE) {
            Memory w = new MapWrapper(underlying, (int) (i - s));
            setMemoryBlockAt((int) i, w);
        }
    }

    /**
     * Maps the given block into the given address.
     * <p>
     * The supplied block must be <code>BLOCK_SIZE</code> long, and the start
     * address must be <code>BLOCK_SIZE</code> granularity otherwise an
     * <code>IllegalStateException</code> is thrown.
     * @param start address for beginning of <code>block</code>.
     * @param block object to be mapped.
     * @throws java.lang.IllegalStateException if there is an error in the mapping.
     */
    public void mapMemory(int start, Memory block) {
        if((start % BLOCK_SIZE) != 0) {
            System.err.println("Critical error: Illegal map request: start=" + Integer.toHexString(start) + ".");
            throw new IllegalStateException("Cannot allocate memory starting at " + Integer.toHexString(start) + "; this is not aligned to " + BLOCK_SIZE + " bytes");
        }
        if(block.getSize() != BLOCK_SIZE) {
            System.err.println("Critical error: Illegal map request: Impossible underlying memory size.");
            throw new IllegalStateException("Can only allocate memory in blocks of " + BLOCK_SIZE);
        }
        unmap(start, BLOCK_SIZE);

        long s = 0xFFFFFFFFl & start;
        setMemoryBlockAt((int) s, block);
    }

    public static final class UnconnectedMemoryBlock implements Memory {

        public void dumpSRPartial(SRDumper output) throws IOException
        {
        }

        public UnconnectedMemoryBlock(SRLoader input) throws IOException
        {
            input.objectCreated(this);
        }

        public UnconnectedMemoryBlock()
        {
        }

        public void dumpStatusPartial(StatusDumper output)
        {
            //super.dumpStatusPartial(output); <no superclass 20090704>
        }

        public void dumpStatus(StatusDumper output)
        {
            if(output.dumped(this))
                return;

            output.println("#" + output.objectNumber(this) + ": UnconnectedMemoryBlock:");
            dumpStatusPartial(output);
            output.endObject();
        }

        public boolean isAllocated() {
            return false;
        }

        public void clear() {
        }

        public void clear(int start, int length) {
        }

        public void copyContentsIntoArray(int address, byte[] buffer, int off, int len) {
        }

        public void copyArrayIntoContents(int address, byte[] buffer, int off, int len) {
            System.err.println("Critical error: Illegal map request: Cannot load array into unconnected memory block.");
            throw new IllegalStateException("Cannot load array into unconnected memory block");
        }

        public long getSize() {
            return BLOCK_SIZE;
        }

        public byte getByte(int offset) {
            return (byte) -1;
        }

        public short getWord(int offset) {
            return (short) -1;
        }

        public int getDoubleWord(int offset) {
            return -1;
        }

        public long getQuadWord(int offset) {
            return -1l;
        }

        public long getLowerDoubleQuadWord(int offset) {
            return -1l;
        }

        public long getUpperDoubleQuadWord(int offset) {
            return -1l;
        }

        public void setByte(int offset, byte data) {
        }

        public void setWord(int offset, short data) {
        }

        public void setDoubleWord(int offset, int data) {
        }

        public void setQuadWord(int offset, long data) {
        }

        public void setLowerDoubleQuadWord(int offset, long data) {
        }

        public void setUpperDoubleQuadWord(int offset, long data) {
        }

        public int executeReal(Processor cpu, int offset)
        {
            System.err.println("Critical error: Can not execute in unconnected memory.");
            throw new IllegalStateException("Trying to execute in Unconnected Block @ 0x" + Integer.toHexString(offset));
        }

        public int executeProtected(Processor cpu, int offset)
        {
            System.err.println("Critical error: Can not execute in unconnected memory.");
            throw new IllegalStateException("Trying to execute in Unconnected Block @ 0x" + Integer.toHexString(offset));
        }

        public int executeVirtual8086(Processor cpu, int offset)
        {
            System.err.println("Critical error: Can not execute in unconnected memory.");
            throw new IllegalStateException("Trying to execute in Unconnected Block @ 0x" + Integer.toHexString(offset));
        }

        public String toString()
        {
            return "Unconnected Memory";
        }

        public void loadInitialContents(int address, byte[] buf, int off, int len)
        {
        }
    }

    public void reset()
    {
        clear();
        setGateA20State(false);
        linearAddr = null;
    }

    public boolean initialised()
    {
        return (linearAddr != null);
    }

    public void acceptComponent(HardwareComponent component)
    {
        if(component instanceof LinearAddressSpace)
            linearAddr = (LinearAddressSpace)component;
    }

    public String toString()
    {
        return "Physical Address Bus";
    }

    private Memory getMemoryBlockAt(int i)
    {
        try {
            return quickIndex[i >>> INDEX_SHIFT];
        } catch (ArrayIndexOutOfBoundsException e) {
            try {
                return index[i >>> TOP_INDEX_SHIFT][(i >>> BOTTOM_INDEX_SHIFT) & BOTTOM_INDEX_MASK];
            } catch (NullPointerException n) {
                return UNCONNECTED;
            }
        }
    }

    private void setMemoryBlockAt(int i, Memory b) {
        try {
            int idx = i >>> INDEX_SHIFT;
            quickNonA20MaskedIndex[idx] = b;
            if((idx & (GATEA20_MASK >>> INDEX_SHIFT)) == idx) {
                quickA20MaskedIndex[idx] = b;
                quickA20MaskedIndex[idx | ((~GATEA20_MASK) >>> INDEX_SHIFT)] = b;
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            try {
                nonA20MaskedIndex[i >>> TOP_INDEX_SHIFT][(i >>> BOTTOM_INDEX_SHIFT) & BOTTOM_INDEX_MASK] = b;
            } catch (NullPointerException n) {
                nonA20MaskedIndex[i >>> TOP_INDEX_SHIFT] = new Memory[BOTTOM_INDEX_SIZE];
                nonA20MaskedIndex[i >>> TOP_INDEX_SHIFT][(i >>> BOTTOM_INDEX_SHIFT) & BOTTOM_INDEX_MASK] = b;
            }

            if((i & GATEA20_MASK) == i) {
                try {
                    a20MaskedIndex[i >>> TOP_INDEX_SHIFT][(i >>> BOTTOM_INDEX_SHIFT) & BOTTOM_INDEX_MASK] = b;
                } catch (NullPointerException n) {
                    a20MaskedIndex[i >>> TOP_INDEX_SHIFT] = new Memory[BOTTOM_INDEX_SIZE];
                    a20MaskedIndex[i >>> TOP_INDEX_SHIFT][(i >>> BOTTOM_INDEX_SHIFT) & BOTTOM_INDEX_MASK] = b;
                }

                int modi = i | ~GATEA20_MASK;
                try {
                    a20MaskedIndex[modi >>> TOP_INDEX_SHIFT][(modi >>> BOTTOM_INDEX_SHIFT) & BOTTOM_INDEX_MASK] = b;
                } catch (NullPointerException n) {
                    a20MaskedIndex[modi >>> TOP_INDEX_SHIFT] = new Memory[BOTTOM_INDEX_SIZE];
                    a20MaskedIndex[modi >>> TOP_INDEX_SHIFT][(modi >>> BOTTOM_INDEX_SHIFT) & BOTTOM_INDEX_MASK] = b;
                }
            }
        }
    }

    public void loadInitialContents(int address, byte[] buf, int off, int len) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public void pokeRAM(int address, byte val) {
        int rpage = address >>> 12;
        if(rpage >= quickNonA20MaskedIndex.length)
            return;
        Memory page = quickNonA20MaskedIndex[rpage];
        if(!(page instanceof LazyCodeBlockMemory))
            return;
        page.setByte(address & 4095, val);
    }
}
