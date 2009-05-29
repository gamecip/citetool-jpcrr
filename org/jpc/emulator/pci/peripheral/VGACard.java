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

package org.jpc.emulator.pci.peripheral;

import org.jpc.emulator.pci.*;
import org.jpc.emulator.motherboard.*;
import org.jpc.emulator.memory.*;
import org.jpc.emulator.memory.codeblock.*;
import org.jpc.emulator.processor.Processor;
import org.jpc.support.*;
import org.jpc.emulator.*;
import java.util.*;
import java.io.*;

public class VGACard extends AbstractPCIDevice implements IOPortCapable, HardwareComponent
{
    //VGA_RAM_SIZE must be a power of two
    private static final int VGA_RAM_SIZE = 4096 * 1024;
    private static final int INIT_VGA_RAM_SIZE = 64 * 1024;
    private static final int PAGE_SHIFT = 12;

    private final int[] expand4 = new int[256];
    private final int[] expand2 = new int[256];
    private final int[] expand4to8 = new int[16];

    private static final int MOR_COLOR_EMULATION = 0x01;
    private static final int ST01_V_RETRACE = 0x08;
    private static final int ST01_DISP_ENABLE = 0x01;
    private static final int VBE_DISPI_MAX_XRES = 1024;
    private static final int VBE_DISPI_MAX_YRES = 768;

    private static final int VBE_DISPI_INDEX_ID = 0x0;
    private static final int VBE_DISPI_INDEX_XRES = 0x1;
    private static final int VBE_DISPI_INDEX_YRES = 0x2;
    private static final int VBE_DISPI_INDEX_BPP = 0x3;
    private static final int VBE_DISPI_INDEX_ENABLE = 0x4;
    private static final int VBE_DISPI_INDEX_BANK = 0x5;
    private static final int VBE_DISPI_INDEX_VIRT_WIDTH = 0x6;
    private static final int VBE_DISPI_INDEX_VIRT_HEIGHT = 0x7;
    private static final int VBE_DISPI_INDEX_X_OFFSET = 0x8;
    private static final int VBE_DISPI_INDEX_Y_OFFSET = 0x9;
    private static final int VBE_DISPI_INDEX_NB = 0xa;

    private static final int VBE_DISPI_ID0 = 0xB0C0;
    private static final int VBE_DISPI_ID1 = 0xB0C1;
    private static final int VBE_DISPI_ID2 = 0xB0C2;

    private static final int VBE_DISPI_DISABLED = 0x00;
    private static final int VBE_DISPI_ENABLED = 0x01;
    private static final int VBE_DISPI_LFB_ENABLED = 0x40;
    private static final int VBE_DISPI_NOCLEARMEM = 0x80;
    private static final int VBE_DISPI_LFB_PHYSICAL_ADDRESS = 0xE0000000;

    private static final int GMODE_TEXT = 0;
    private static final int GMODE_GRAPH = 1;
    private static final int GMODE_BLANK = 2;

    private static final int CH_ATTR_SIZE = (160 * 100);
    private static final int VGA_MAX_HEIGHT = 1024;

    private static final int GR_INDEX_SETRESET = 0x00;
    private static final int GR_INDEX_ENABLE_SETRESET = 0x01;
    private static final int GR_INDEX_COLOR_COMPARE = 0x02;
    private static final int GR_INDEX_DATA_ROTATE = 0x03;
    private static final int GR_INDEX_READ_MAP_SELECT = 0x04;
    private static final int GR_INDEX_GRAPHICS_MODE = 0x05;
    private static final int GR_INDEX_MISC = 0x06;
    private static final int GR_INDEX_COLOR_DONT_CARE = 0x07;
    private static final int GR_INDEX_BITMASK = 0x08;

    private static final int SR_INDEX_RESET = 0x00;
    private static final int SR_INDEX_CLOCKING_MODE = 0x01;
    private static final int SR_INDEX_MAP_MASK = 0x02;
    private static final int SR_INDEX_CHAR_MAP_SELECT = 0x03;
    private static final int SR_INDEX_SEQ_MEMORY_MODE = 0x04;

    private static final int AR_INDEX_PALLETE_MIN = 0x00;
    private static final int AR_INDEX_PALLETE_MAX = 0x0F;
    private static final int AR_INDEX_ATTR_MODE_CONTROL = 0x10;
    private static final int AR_INDEX_OVERSCAN_COLOR = 0x11;
    private static final int AR_INDEX_COLOR_PLANE_ENABLE = 0x12;
    private static final int AR_INDEX_HORIZ_PIXEL_PANNING = 0x13;
    private static final int AR_INDEX_COLOR_SELECT = 0x14;

    private static final int CR_INDEX_HORZ_DISPLAY_END = 0x01;
    private static final int CR_INDEX_VERT_TOTAL = 0x06;
    private static final int CR_INDEX_OVERFLOW = 0x07;
    private static final int CR_INDEX_MAX_SCANLINE = 0x09;
    private static final int CR_INDEX_CURSOR_START = 0x0a;
    private static final int CR_INDEX_CURSOR_END = 0x0b;
    private static final int CR_INDEX_START_ADDR_HIGH = 0x0c;
    private static final int CR_INDEX_START_ADDR_LOW = 0x0d;
    private static final int CR_INDEX_CURSOR_LOC_HIGH = 0x0e;
    private static final int CR_INDEX_CURSOR_LOC_LOW = 0x0f;
    private static final int CR_INDEX_VERT_RETRACE_END = 0x11;
    private static final int CR_INDEX_VERT_DISPLAY_END = 0x12;
    private static final int CR_INDEX_OFFSET = 0x13;
    private static final int CR_INDEX_CRTC_MODE_CONTROL = 0x17;
    private static final int CR_INDEX_LINE_COMPARE = 0x18;

    private static final int[] sequencerRegisterMask = new int[]{
        0x03, //~0xfc,
        0x3d, //~0xc2,
        0x0f, //~0xf0,
        0x3f, //~0xc0,
        0x0e, //~0xf1,
        0x00, //~0xff,
        0x00, //~0xff,
        0xff //~0x00
    };

    private static final int[] graphicsRegisterMask = new int[]{
        0x0f, //~0xf0
        0x0f, //~0xf0
        0x0f, //~0xf0
        0x1f, //~0xe0
        0x03, //~0xfc
        0x7b, //~0x84
        0x0f, //~0xf0
        0x0f, //~0xf0
        0xff, //~0x00
        0x00, //~0xff
        0x00, //~0xff
        0x00, //~0xff
        0x00, //~0xff
        0x00, //~0xff
        0x00, //~0xff
        0x00 //~0xff
    };

    private static final int[] mask16 = new int[]{
        0x00000000,
        0x000000ff,
        0x0000ff00,
        0x0000ffff,
        0x00ff0000,
        0x00ff00ff,
        0x00ffff00,
        0x00ffffff,
        0xff000000,
        0xff0000ff,
        0xff00ff00,
        0xff00ffff,
        0xffff0000,
        0xffff00ff,
        0xffffff00,
        0xffffffff
    };

    private static final int[] dmask16 = new int[]{
        0x00000000,
        0xff000000,
        0x00ff0000,
        0xffff0000,
        0x0000ff00,
        0xff00ff00,
        0x00ffff00,
        0xffffff00,
        0x000000ff,
        0xff0000ff,
        0x00ff00ff,
        0xffff00ff,
        0x0000ffff,
        0xff00ffff,
        0x00ffffff,
        0xffffffff
    };

    private static final int[] dmask4 = new int[]{
        0x00000000,
        0xffff0000,
        0x0000ffff,
        0xffffffff
    };

    private static final int[] cursorGlyph = new int[]{
        0xffffffff, 0xffffffff,
        0xffffffff, 0xffffffff,
        0xffffffff, 0xffffffff,
        0xffffffff, 0xffffffff,
        0xffffffff, 0xffffffff,
        0xffffffff, 0xffffffff,
        0xffffffff, 0xffffffff,
        0xffffffff, 0xffffffff,
        0xffffffff, 0xffffffff,
        0xffffffff, 0xffffffff,
        0xffffffff, 0xffffffff,
        0xffffffff, 0xffffffff,
        0xffffffff, 0xffffffff,
        0xffffffff, 0xffffffff,
        0xffffffff, 0xffffffff,
        0xffffffff, 0xffffffff};

    private GraphicsUpdater VGA_DRAW_LINE2;
    private GraphicsUpdater VGA_DRAW_LINE2D2;
    private GraphicsUpdater VGA_DRAW_LINE4;
    private GraphicsUpdater VGA_DRAW_LINE4D2;
    private GraphicsUpdater VGA_DRAW_LINE8D2;
    private GraphicsUpdater VGA_DRAW_LINE8;
    private GraphicsUpdater VGA_DRAW_LINE15;
    private GraphicsUpdater VGA_DRAW_LINE16;
    private GraphicsUpdater VGA_DRAW_LINE24;
    private GraphicsUpdater VGA_DRAW_LINE32;

    private int latch;
    private int sequencerRegisterIndex, graphicsRegisterIndex, attributeRegisterIndex, crtRegisterIndex;
    private int[] sequencerRegister, graphicsRegister, attributeRegister, crtRegister;
    //private int[] invalidatedYTable;

    private boolean attributeRegisterFlipFlop;
    private int miscellaneousOutputRegister;
    private int featureControlRegister;
    private int st00, st01; // status 0 and 1
    private int dacReadIndex, dacWriteIndex, dacSubIndex, dacState;
    private int shiftControl, doubleScan;
    private int[] dacCache;
    private int[] palette;
    private int bankOffset;

    private int vbeIndex;
    private int[] vbeRegs;
    private int vbeStartAddress;
    private int vbeLineOffset;
    private int vbeBankMask;

    private int[] fontOffset;
    private int graphicMode;
    private int lineOffset;
    private int lineCompare;
    private int startAddress;
    private int planeUpdated;
    private int lastCW, lastCH;
    private int lastWidth, lastHeight;
    private int lastScreenWidth, lastScreenHeight;
    private int cursorStart, cursorEnd;
    private int cursorOffset;
    private final int[] lastPalette;
    private int[] lastChar;

    private TraceTrap traceTrap;

    private boolean ioportRegistered;
    private boolean pciRegistered;
    private boolean memoryRegistered;

    private boolean updatingScreen;

    private VGARAMIORegion ioRegion;
    private VGALowMemoryRegion lowIORegion;
    private VGADigitalOut digitalOut;


    private Magic magic;

    private static final long TRACE_TIME = 15000000;
    private static final long FRAME_TIME = 16666667;

    private Clock timeSource;
    private org.jpc.emulator.Timer retraceTimer;
    private boolean retracing;
    private long nextTimerExpiry;

    public VGADigitalOut getDigitalOut()
    {
        return digitalOut;
    }

    public void dumpStatusPartial(org.jpc.support.StatusDumper output)
    {
        super.dumpStatusPartial(output);
        output.println("\tlatch " + latch + " sequencerRegisterIndex " + sequencerRegisterIndex);
        output.println("\tgraphicsRegisterIndex " + graphicsRegisterIndex);
        output.println("\tattributeRegisterIndex " + attributeRegisterIndex);
        output.println("\tcrtRegisterIndex " + crtRegisterIndex + " bankOffset " + bankOffset);
        output.println("\tattributeRegisterFlipFlop " + attributeRegisterFlipFlop);
        output.println("\tmiscellaneousOutputRegister " + miscellaneousOutputRegister);
        output.println("\tfeatureControlRegister " + featureControlRegister + " doubleScan " + doubleScan);
        output.println("\tst00 " + st00 + " st01 " + st01 + " shiftControl " + shiftControl);
        output.println("\tdacReadIndex " + dacReadIndex + " dacWriteIndex " + dacWriteIndex);
        output.println("\tdacSubIndex " + dacSubIndex + " dacState " + dacState);
        output.println("\tvbeIndex " + vbeIndex + " vbeStartAddress " + vbeStartAddress);
        output.println("\tvbeLineOffset " + vbeLineOffset + " vbeBankMask " + vbeBankMask);
        output.println("\tgraphicMode " + graphicMode + " lineOffset " + lineOffset);
        output.println("\tlineCompare " + lineCompare + " startAddress " + startAddress);
        output.println("\tplaneUpdated " + planeUpdated + " lastCW " + lastCW + " lastCH " + lastCH);
        output.println("\tlastWidth " + lastWidth + " lastHeight " + lastHeight);
        output.println("\tlastScreenWidth " + lastScreenWidth + " lastScreenHeight " + lastScreenHeight);
        output.println("\tcursorStart " + cursorStart + " cursorEnd " + cursorEnd);
        output.println("\tcursorOffset " + cursorOffset + " ioportRegistered " + ioportRegistered);
        output.println("\tpciRegistered " + pciRegistered + " memoryRegistered " + memoryRegistered);
        output.println("\tupdatingScreen " + updatingScreen + " retracing " + retracing);
        output.println("\tnextTimerExpiry " + nextTimerExpiry);

        output.println("\tVGA_DRAW_LINE2 <object #" + output.objectNumber(VGA_DRAW_LINE2) + ">"); if(VGA_DRAW_LINE2 != null) VGA_DRAW_LINE2.dumpStatus(output);
        output.println("\tVGA_DRAW_LINE2D2 <object #" + output.objectNumber(VGA_DRAW_LINE2D2) + ">"); if(VGA_DRAW_LINE2D2 != null) VGA_DRAW_LINE2D2.dumpStatus(output);
        output.println("\tVGA_DRAW_LINE4 <object #" + output.objectNumber(VGA_DRAW_LINE4) + ">"); if(VGA_DRAW_LINE4 != null) VGA_DRAW_LINE4.dumpStatus(output);
        output.println("\tVGA_DRAW_LINE4D2 <object #" + output.objectNumber(VGA_DRAW_LINE4D2) + ">"); if(VGA_DRAW_LINE4D2 != null) VGA_DRAW_LINE4D2.dumpStatus(output);
        output.println("\tVGA_DRAW_LINE8 <object #" + output.objectNumber(VGA_DRAW_LINE8) + ">"); if(VGA_DRAW_LINE8 != null) VGA_DRAW_LINE8.dumpStatus(output);
        output.println("\tVGA_DRAW_LINE8D2 <object #" + output.objectNumber(VGA_DRAW_LINE8D2) + ">"); if(VGA_DRAW_LINE8D2 != null) VGA_DRAW_LINE8D2.dumpStatus(output);
        output.println("\tVGA_DRAW_LINE15 <object #" + output.objectNumber(VGA_DRAW_LINE15) + ">"); if(VGA_DRAW_LINE15 != null) VGA_DRAW_LINE15.dumpStatus(output);
        output.println("\tVGA_DRAW_LINE16 <object #" + output.objectNumber(VGA_DRAW_LINE16) + ">"); if(VGA_DRAW_LINE16 != null) VGA_DRAW_LINE16.dumpStatus(output);
        output.println("\tVGA_DRAW_LINE24 <object #" + output.objectNumber(VGA_DRAW_LINE24) + ">"); if(VGA_DRAW_LINE24 != null) VGA_DRAW_LINE24.dumpStatus(output);
        output.println("\tVGA_DRAW_LINE32 <object #" + output.objectNumber(VGA_DRAW_LINE32) + ">"); if(VGA_DRAW_LINE32 != null) VGA_DRAW_LINE32.dumpStatus(output);
        output.println("\ttraceTrap <object #" + output.objectNumber(traceTrap) + ">"); if(traceTrap != null) traceTrap.dumpStatus(output);
        output.println("\tioRegion <object #" + output.objectNumber(ioRegion) + ">"); if(ioRegion != null) ioRegion.dumpStatus(output);
        output.println("\tlowIORegion <object #" + output.objectNumber(lowIORegion) + ">"); if(lowIORegion != null) lowIORegion.dumpStatus(output);
        output.println("\tretraceTimer <object #" + output.objectNumber(retraceTimer) + ">"); if(retraceTimer != null) retraceTimer.dumpStatus(output);
        output.println("\ttimeSource <object #" + output.objectNumber(timeSource) + ">"); if(timeSource != null) timeSource.dumpStatus(output);
        output.println("\tdigitalOut <object #" + output.objectNumber(digitalOut) + ">"); if(digitalOut != null) digitalOut.dumpStatus(output);
        output.printArray(sequencerRegister, "sequencerRegister");
        output.printArray(graphicsRegister, "graphicsRegister");
        output.printArray(attributeRegister, "attributeRegister");
        output.printArray(crtRegister, "crtRegister");
        output.printArray(dacCache, "dacCache");
        output.printArray(palette, "palette");
        output.printArray(vbeRegs, "vbeRegs");
        output.printArray(fontOffset, "fontOffset");
        output.printArray(lastPalette, "lastPalette");
        output.printArray(lastChar, "lastChar");

    }

    public void dumpStatus(org.jpc.support.StatusDumper output)
    {
        if(output.dumped(this))
            return;

        output.println("#" + output.objectNumber(this) + ": VGACard:");
        dumpStatusPartial(output);
        output.endObject();
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
        output.dumpObject(VGA_DRAW_LINE2);
        output.dumpObject(VGA_DRAW_LINE2D2);
        output.dumpObject(VGA_DRAW_LINE4);
        output.dumpObject(VGA_DRAW_LINE4D2);
        output.dumpObject(VGA_DRAW_LINE8);
        output.dumpObject(VGA_DRAW_LINE8D2);
        output.dumpObject(VGA_DRAW_LINE15);
        output.dumpObject(VGA_DRAW_LINE16);
        output.dumpObject(VGA_DRAW_LINE24);
        output.dumpObject(VGA_DRAW_LINE32);
        output.dumpInt(latch);
        output.dumpInt(sequencerRegisterIndex); 
        output.dumpInt(graphicsRegisterIndex); 
        output.dumpInt(attributeRegisterIndex); 
        output.dumpInt(crtRegisterIndex);
        output.dumpArray(sequencerRegister);
        output.dumpArray(graphicsRegister);
        output.dumpArray(attributeRegister);
        output.dumpArray(crtRegister);
        output.dumpBoolean(attributeRegisterFlipFlop);
        output.dumpInt(miscellaneousOutputRegister);
        output.dumpInt(featureControlRegister);
        output.dumpInt(st00);
        output.dumpInt(st01);
        output.dumpInt(dacReadIndex);
        output.dumpInt(dacWriteIndex);
        output.dumpInt(dacSubIndex);
        output.dumpInt(dacState);
        output.dumpInt(shiftControl);
        output.dumpInt(doubleScan);
        output.dumpArray(dacCache);
        output.dumpArray(palette);
        output.dumpInt(bankOffset);
        output.dumpInt(vbeIndex);
        output.dumpArray(vbeRegs);
        output.dumpInt(vbeStartAddress);
        output.dumpInt(vbeLineOffset);
        output.dumpInt(vbeBankMask);
        output.dumpArray(fontOffset);
        output.dumpInt(graphicMode);
        output.dumpInt(lineOffset);
        output.dumpInt(lineCompare);
        output.dumpInt(startAddress);
        output.dumpInt(planeUpdated);
        output.dumpInt(lastCW);
        output.dumpInt(lastCH);
        output.dumpInt(lastWidth);
        output.dumpInt(lastHeight);
        output.dumpInt(lastScreenWidth);
        output.dumpInt(lastScreenHeight);
        output.dumpInt(cursorStart);
        output.dumpInt(cursorEnd);
        output.dumpInt(cursorOffset);
        output.dumpArray(lastPalette);
        output.dumpArray(lastChar);
        output.dumpObject(traceTrap);
        output.dumpBoolean(ioportRegistered);
        output.dumpBoolean(pciRegistered);
        output.dumpBoolean(memoryRegistered);
        output.dumpBoolean(updatingScreen);
        output.dumpObject(ioRegion);
        output.dumpObject(lowIORegion);
        output.dumpObject(timeSource);
        output.dumpObject(retraceTimer);
        output.dumpBoolean(retracing);
        output.dumpLong(nextTimerExpiry);
        output.dumpObject(digitalOut);
    }

    public VGACard(org.jpc.support.SRLoader input) throws IOException
    {
        super(input);
        VGA_DRAW_LINE2 = (GraphicsUpdater)(input.loadObject());
        VGA_DRAW_LINE2D2 = (GraphicsUpdater)(input.loadObject());
        VGA_DRAW_LINE4 = (GraphicsUpdater)(input.loadObject());
        VGA_DRAW_LINE4D2 = (GraphicsUpdater)(input.loadObject());
        VGA_DRAW_LINE8 = (GraphicsUpdater)(input.loadObject());
        VGA_DRAW_LINE8D2 = (GraphicsUpdater)(input.loadObject());
        VGA_DRAW_LINE15 = (GraphicsUpdater)(input.loadObject());
        VGA_DRAW_LINE16 = (GraphicsUpdater)(input.loadObject());
        VGA_DRAW_LINE24 = (GraphicsUpdater)(input.loadObject());
        VGA_DRAW_LINE32 = (GraphicsUpdater)(input.loadObject());
        latch = input.loadInt();
        sequencerRegisterIndex = input.loadInt(); 
        graphicsRegisterIndex = input.loadInt(); 
        attributeRegisterIndex = input.loadInt();
        crtRegisterIndex = input.loadInt();
        sequencerRegister = input.loadArrayInt();
        graphicsRegister = input.loadArrayInt();
        attributeRegister = input.loadArrayInt();
        crtRegister = input.loadArrayInt();
        attributeRegisterFlipFlop = input.loadBoolean();
        miscellaneousOutputRegister = input.loadInt();
        featureControlRegister = input.loadInt();
        st00 = input.loadInt();
        st01 = input.loadInt();
        dacReadIndex = input.loadInt();
        dacWriteIndex = input.loadInt();
        dacSubIndex = input.loadInt();
        dacState = input.loadInt();
        shiftControl = input.loadInt();
        doubleScan = input.loadInt();
        dacCache = input.loadArrayInt();
        palette = input.loadArrayInt();
        bankOffset = input.loadInt();
        vbeIndex = input.loadInt();
        vbeRegs = input.loadArrayInt();
        vbeStartAddress = input.loadInt();
        vbeLineOffset = input.loadInt();
        vbeBankMask = input.loadInt();
        fontOffset = input.loadArrayInt();
        graphicMode = input.loadInt();
        lineOffset = input.loadInt();
        lineCompare = input.loadInt();
        startAddress = input.loadInt();
        planeUpdated = input.loadInt();
        lastCW = input.loadInt();
        lastCH = input.loadInt();
        lastWidth = input.loadInt();
        lastHeight = input.loadInt();
        lastScreenWidth = input.loadInt();
        lastScreenHeight = input.loadInt();
        cursorStart = input.loadInt();
        cursorEnd = input.loadInt();
        cursorOffset = input.loadInt();
        lastPalette = input.loadArrayInt();
        lastChar = input.loadArrayInt();
        traceTrap = (TraceTrap)(input.loadObject());
        ioportRegistered = input.loadBoolean();
        pciRegistered = input.loadBoolean();
        memoryRegistered = input.loadBoolean();
        updatingScreen = input.loadBoolean();
        ioRegion = (VGARAMIORegion)(input.loadObject());
        lowIORegion = (VGALowMemoryRegion)(input.loadObject());
        timeSource = (Clock)(input.loadObject());
        retraceTimer = (org.jpc.emulator.Timer)(input.loadObject());
        retracing = input.loadBoolean();
        nextTimerExpiry = input.loadLong();
        digitalOut = (VGADigitalOut)(input.loadObject());
    }

    public static org.jpc.SRDumpable loadSR(org.jpc.support.SRLoader input, Integer id) throws IOException
    {
        org.jpc.SRDumpable x = new VGACard(input);
        input.endObject();
        return x;
    }

    public VGACard()
    {
        magic = new Magic(Magic.VGA_CARD_MAGIC_V2);
        ioportRegistered = false;
        memoryRegistered = false;
        pciRegistered = false;
        retracing = false;
        timeSource = null;
        retraceTimer = null;
        nextTimerExpiry = TRACE_TIME;
        digitalOut = new VGADigitalOut();
        setupArrays();
        setupGraphicsModes();

        assignDevFN(-1);
        setIRQIndex(16);

        putConfigByte(0x00, (byte)0x34); // dummy VGA (same as Bochs ID)
        putConfigByte(0x01, (byte)0x12);
        putConfigByte(0x02, (byte)0x11);
        putConfigByte(0x03, (byte)0x11);
        putConfigByte(0x0a, (byte)0x00); // VGA Controller
        putConfigByte(0x0b, (byte)0x03);
        putConfigByte(0x0e, (byte)0x00); // header_type

        sequencerRegister = new int[256];
        graphicsRegister = new int[256];
        attributeRegister = new int[256];
        crtRegister = new int[256];
        //invalidatedYTable = new int[VGA_MAX_HEIGHT / 32];

        dacCache = new int[3];
        palette = new int[768];

        ioRegion = new VGARAMIORegion();
        lowIORegion = new VGALowMemoryRegion();

        vbeRegs = new int[VBE_DISPI_INDEX_NB];

        fontOffset = new int[2];
        lastPalette = new int[256];
        lastChar = new int[CH_ATTR_SIZE];

        this.internalReset();

        bankOffset = 0;

        vbeRegs[VBE_DISPI_INDEX_ID] = VBE_DISPI_ID0;
        vbeBankMask = ((VGA_RAM_SIZE >>> 16) - 1);
    }

    public void dumpState(DataOutput output) throws IOException
    {
        magic.dumpState(output);
        super.dumpState(output);

        output.writeBoolean(retracing);
        retraceTimer.dumpState(output);
        output.writeLong(nextTimerExpiry);

        output.writeInt(expand4.length);
        for (int i = 0; i< expand4.length; i++)
            output.writeInt(expand4[i]);
        output.writeInt(expand2.length);
        for (int i = 0; i< expand2.length; i++)
            output.writeInt(expand2[i]);
        output.writeInt(expand4to8.length);
        for (int i = 0; i< expand4to8.length; i++)
            output.writeInt(expand4to8[i]);
        output.writeInt(latch);
        output.writeInt(sequencerRegisterIndex);
        output.writeInt(graphicsRegisterIndex);
        output.writeInt(attributeRegisterIndex);
        output.writeInt(crtRegisterIndex);
        output.writeInt(sequencerRegister.length);
        for (int i = 0; i< sequencerRegister.length; i++)
            output.writeInt(sequencerRegister[i]);
        output.writeInt(graphicsRegister.length);
        for (int i = 0; i< graphicsRegister.length; i++)
            output.writeInt(graphicsRegister[i]);
        output.writeInt(attributeRegister.length);
        for (int i = 0; i< attributeRegister.length; i++)
            output.writeInt(attributeRegister[i]);
        output.writeInt(crtRegister.length);
        for (int i = 0; i< crtRegister.length; i++)
            output.writeInt(crtRegister[i]);
        output.writeBoolean(attributeRegisterFlipFlop);
        output.writeInt(miscellaneousOutputRegister);
        output.writeInt(featureControlRegister);
        output.writeInt(st00);
        output.writeInt(st01);
        output.writeInt(dacReadIndex);
        output.writeInt(dacWriteIndex);
        output.writeInt(dacSubIndex);
        output.writeInt(dacState);
        output.writeInt(shiftControl);
        output.writeInt(doubleScan);
        output.writeInt(dacCache.length);
        for (int i = 0; i< dacCache.length; i++)
            output.writeInt(dacCache[i]);
        output.writeInt(palette.length);
         for (int i = 0; i< palette.length; i++)
             output.writeInt(palette[i]);
        output.writeInt(bankOffset);
        output.writeInt(vbeIndex);
        output.writeInt(vbeRegs.length);
        for (int i = 0; i< vbeRegs.length; i++)
            output.writeInt(vbeRegs[i]);
        output.writeInt(vbeStartAddress);
        output.writeInt(vbeLineOffset);
        output.writeInt(vbeBankMask);
        output.writeInt(fontOffset.length);
        for (int i = 0; i< fontOffset.length; i++)
            output.writeInt(fontOffset[i]);
        output.writeInt(graphicMode);
        output.writeInt(lineOffset);
        output.writeInt(lineCompare);
        output.writeInt(startAddress);
        output.writeInt(planeUpdated);
        output.writeInt(lastCW);
        output.writeInt(lastCH);
        output.writeInt(lastWidth);
        output.writeInt(lastHeight);
        output.writeInt(lastScreenWidth);
        output.writeInt(lastScreenHeight);
        output.writeInt(cursorStart);
        output.writeInt(cursorEnd);
        output.writeInt(cursorOffset);
        output.writeInt(lastPalette.length);
        for (int i = 0; i< lastPalette.length; i++)
            output.writeInt(lastPalette[i]);
        output.writeInt(lastChar.length);
        for (int i = 0; i< lastChar.length; i++)
            output.writeInt(lastChar[i]);
        output.writeBoolean(updatingScreen);
        //dump ioregion
        ioRegion.dumpState(output);

        //dump graphics updaters
        VGA_DRAW_LINE2.dumpState(output);
        VGA_DRAW_LINE2D2.dumpState(output);
        VGA_DRAW_LINE4.dumpState(output);
        VGA_DRAW_LINE4D2.dumpState(output);
        VGA_DRAW_LINE8D2.dumpState(output);
        VGA_DRAW_LINE8.dumpState(output);
        VGA_DRAW_LINE15.dumpState(output);
        VGA_DRAW_LINE16.dumpState(output);
        VGA_DRAW_LINE24.dumpState(output);
        VGA_DRAW_LINE32.dumpState(output);
    }

    public void loadState(DataInput input) throws IOException
    {
        magic.loadState(input);
        super.loadState(input);
        ioportRegistered =false;
        pciRegistered = false;
        memoryRegistered = false;

        retracing = input.readBoolean();
        retraceTimer.loadState(input);
        nextTimerExpiry = input.readLong();

        int len = input.readInt();
        for (int i = 0; i< len; i++)
            expand4[i] = input.readInt();
        len = input.readInt();
        for (int i = 0; i< len; i++)
            expand2[i] = input.readInt();
        len = input.readInt();
        for (int i = 0; i< expand4to8.length; i++)
            expand4to8[i] = input.readInt();
        latch = input.readInt();
        sequencerRegisterIndex = input.readInt();
        graphicsRegisterIndex = input.readInt();
        attributeRegisterIndex = input.readInt();
        crtRegisterIndex = input.readInt();
        len = input.readInt();
        sequencerRegister = new int[len];
        for (int i = 0; i< sequencerRegister.length; i++)
            sequencerRegister[i] = input.readInt();
        len = input.readInt();
        graphicsRegister = new int[len];
        for (int i = 0; i< graphicsRegister.length; i++)
            graphicsRegister[i] = input.readInt();
        len = input.readInt();
        attributeRegister = new int[len];
        for (int i = 0; i< attributeRegister.length; i++)
            attributeRegister[i] = input.readInt();
        len = input.readInt();
        crtRegister = new int[len];
        for (int i = 0; i< crtRegister.length; i++)
            crtRegister[i] = input.readInt();
        attributeRegisterFlipFlop = input.readBoolean();
        miscellaneousOutputRegister = input.readInt();
        featureControlRegister = input.readInt();
        st00 = input.readInt();
        st01 = input.readInt();
        dacReadIndex = input.readInt();
        dacWriteIndex = input.readInt();
        dacSubIndex = input.readInt();
        dacState = input.readInt();
        shiftControl = input.readInt();
        doubleScan = input.readInt();
        len = input.readInt();
        dacCache = new int[len];
        for (int i = 0; i< dacCache.length; i++)
            dacCache[i] = input.readInt();
        len = input.readInt();
        palette = new int[len];
        for (int i = 0; i< palette.length; i++)
            palette[i] = input.readInt();
        bankOffset = input.readInt();
        vbeIndex = input.readInt();
        len = input.readInt();
        vbeRegs = new int[len];
        for (int i = 0; i< vbeRegs.length; i++)
            vbeRegs[i] = input.readInt();
        vbeStartAddress = input.readInt();
        vbeLineOffset = input.readInt();
        vbeBankMask = input.readInt();
        len = input.readInt();
        fontOffset = new int[len];
        for (int i = 0; i< fontOffset.length; i++)
            fontOffset[i] = input.readInt();
        graphicMode = input.readInt();
        lineOffset = input.readInt();
        lineCompare = input.readInt();
        startAddress = input.readInt();
        planeUpdated = input.readInt();
        lastCW = input.readInt();
        lastCH = input.readInt();
        lastWidth = input.readInt();
        lastHeight = input.readInt();
        lastScreenWidth = input.readInt();
        lastScreenHeight = input.readInt();
        cursorStart = input.readInt();
        cursorEnd = input.readInt();
        cursorOffset = input.readInt();
        len = input.readInt();
        for (int i = 0; i< lastPalette.length; i++)
            lastPalette[i] = input.readInt();
        len = input.readInt();
        lastChar = new int[len];
        for (int i = 0; i< lastChar.length; i++)
            lastChar[i] = input.readInt();
        updatingScreen = input.readBoolean();
        //load ioregion
        ioRegion = new VGARAMIORegion();
        ioRegion.loadState(input);

        //load graphics updaters
        VGA_DRAW_LINE2.loadState(input);
        VGA_DRAW_LINE2D2.loadState(input);
        VGA_DRAW_LINE4.loadState(input);
        VGA_DRAW_LINE4D2.loadState(input);
        VGA_DRAW_LINE8D2.loadState(input);
        VGA_DRAW_LINE8.loadState(input);
        VGA_DRAW_LINE15.loadState(input);
        VGA_DRAW_LINE16.loadState(input);
        VGA_DRAW_LINE24.loadState(input);
        VGA_DRAW_LINE32.loadState(input);

        lowIORegion = new VGALowMemoryRegion();
    }

    public void resizeDisplay(GraphicsDisplay device)
    {
        device.resizeDisplay(lastScreenWidth, lastScreenHeight);
    }


    private void setupArrays()
    {
        for (int i = 0; i < 256; i++) {
            int v = 0;
            for (int j = 0; j < 8; j++) {
                v |= ((i >>> j) & 1) << (j * 4);
            }
            expand4[i] = v;

            v = 0;
            for (int j = 0; j < 4; j++) {
                v |= ((i >>> (2 * j)) & 3) << (j * 4);
            }
            expand2[i] = v;
        }
        for (int i = 0; i < 16; i++) {
            int v = 0;
            for (int j = 0; j < 4; j++) {
                int b = ((i >>> j) & 1);
                v |= b << (2 * j);
                v |= b << (2 * j + 1);
            }
            expand4to8[i] = v;
        }
    }

    private void setupGraphicsModes()
    {
        VGA_DRAW_LINE2 = new DrawLine2();
        VGA_DRAW_LINE2D2 = new DrawLine2d2();
        VGA_DRAW_LINE4 = new DrawLine4();
        VGA_DRAW_LINE4D2 = new DrawLine4d2();
        VGA_DRAW_LINE8D2 = new DrawLine8d2();
        VGA_DRAW_LINE8 = new DrawLine8();
        VGA_DRAW_LINE15 = new DrawLine15();
        VGA_DRAW_LINE16 = new DrawLine16();
        VGA_DRAW_LINE24 = new DrawLine24();
        VGA_DRAW_LINE32 = new DrawLine32();
    }

    //PCIDevice Methods
    public IORegion[] getIORegions()
    {
        return new IORegion[]{ioRegion};
    }

    public IORegion getIORegion(int index)
    {
        if(index == 0)
            return ioRegion;
        else
            return null;
    }

    //IOPortCapable Methods
    public void ioPortWriteByte(int address, int data)
    {
        //all byte accesses are vgaIOPort ones
        vgaIOPortWriteByte(address, data);
    }

    public void ioPortWriteWord(int address, int data)
    {
        switch(address) {
        case 0x1ce:
        case 0xff80:
            vbeIOPortWriteIndex(data);
             break;
        case 0x1cf:
        case 0xff81:
            vbeIOPortWriteData(data);
            break;
        default:
            ioPortWriteByte(address, 0xFF & data);
            ioPortWriteByte(address+1, 0xFF & (data >>> 8));
            break;
        }
    }

    public void ioPortWriteLong(int address, int data)
    {
        ioPortWriteWord(address, 0xFFFF & data);
        ioPortWriteWord(address+2, data >>> 16);
    }

    public int ioPortReadByte(int address)
    {
        //all byte accesses are vgaIOPort ones
        return vgaIOPortReadByte(address);
    }

    public int ioPortReadWord(int address)
    {
        switch(address) {
        case 0x1ce:
        case 0xff80:
            return vbeIOPortReadIndex();
        case 0x1cf:
        case 0xff81:
            return vbeIOPortReadData();
        default:
            int b0 = 0xFF & ioPortReadByte(address);
            int b1 = 0xFF & ioPortReadByte(address+1);
            return b0 | (b1 << 8);
        }
    }

    public int ioPortReadLong(int address)
    {
        int b0 = 0xFFFF & ioPortReadWord(address);
        int b1 = 0xFFFF & ioPortReadWord(address+2);
        return b0 | (b1 << 16);
    }

    public int[] ioPortsRequested()
    {
        return new int[]{0x3b4, 0x3b5, 0x3ba,
                         0x3d4, 0x3d5, 0x3da,
                         0x3c0, 0x3c1, 0x3c2, 0x3c3,
                         0x3c4, 0x3c5, 0x3c6, 0x3c7,
                         0x3c8, 0x3c9, 0x3ca, 0x3cb,
                         0x3cc, 0x3cd, 0x3ce, 0x3cf,
                         0x1ce, 0x1cf, 0xff80, 0xff81
        };
    }

    private final void vgaIOPortWriteByte(int address, int data)
    {
        if ((address >= 0x3b0 && address <= 0x3bf && ((miscellaneousOutputRegister & MOR_COLOR_EMULATION) != 0)) ||
            (address >= 0x3d0 && address <= 0x3df && ((miscellaneousOutputRegister & MOR_COLOR_EMULATION) == 0)))
            return;

        if ((data & ~0xff) != 0)
            System.err.println("Possible Bug With New INT Register");

        switch(address) {
        case 0x3b4:
        case 0x3d4:
            crtRegisterIndex = data;
            break;
        case 0x3b5:
        case 0x3d5:
            if (crtRegisterIndex <= 7 && (crtRegister[CR_INDEX_VERT_RETRACE_END] & 0x80) != 0) {
                /* can always write bit 4 of CR_INDEX_OVERFLOW */
                if (crtRegisterIndex == CR_INDEX_OVERFLOW)
                    crtRegister[CR_INDEX_OVERFLOW] = (crtRegister[CR_INDEX_OVERFLOW] & ~0x10) | (data & 0x10);
                return;
            }
            crtRegister[crtRegisterIndex] = data;
            break;
        case 0x3ba:
        case 0x3da:
            featureControlRegister = data & 0x10;
            break;
        case 0x3c0:
            if (!attributeRegisterFlipFlop) {
                data &= 0x3f;
                attributeRegisterIndex = data;
            } else {
                int index = attributeRegisterIndex & 0x1f;
                switch(index) {
                case AR_INDEX_PALLETE_MIN:
                case 0x01:
                case 0x02:
                case 0x03:
                case 0x04:
                case 0x05:
                case 0x06:
                case 0x07:
                case 0x08:
                case 0x09:
                case 0x0a:
                case 0x0b:
                case 0x0c:
                case 0x0d:
                case 0x0e:
                case AR_INDEX_PALLETE_MAX:
                    attributeRegister[index] = data & 0x3f;
                    break;
                case AR_INDEX_ATTR_MODE_CONTROL:
                    attributeRegister[AR_INDEX_ATTR_MODE_CONTROL] = data & ~0x10;
                    break;
                case AR_INDEX_OVERSCAN_COLOR:
                    attributeRegister[AR_INDEX_OVERSCAN_COLOR] = data;
                    break;
                case AR_INDEX_COLOR_PLANE_ENABLE:
                    attributeRegister[AR_INDEX_COLOR_PLANE_ENABLE] = data & ~0xc0;
                    break;
                case AR_INDEX_HORIZ_PIXEL_PANNING:
                    attributeRegister[AR_INDEX_HORIZ_PIXEL_PANNING] = data & ~0xf0;
                    break;
                case AR_INDEX_COLOR_SELECT:
                    attributeRegister[AR_INDEX_COLOR_SELECT] = data & ~0xf0;
                    break;
                default:
                    break;
                }
            }
            attributeRegisterFlipFlop = !attributeRegisterFlipFlop;
            break;
        case 0x3c2:
            miscellaneousOutputRegister = data & ~0x10;
            break;
        case 0x3c4:
            sequencerRegisterIndex = data & 0x7;
            break;
        case 0x3c5:
            sequencerRegister[sequencerRegisterIndex] = data & sequencerRegisterMask[sequencerRegisterIndex];
            break;
        case 0x3c7:
            dacReadIndex = data;
            dacSubIndex = 0;
            dacState = 3;
            break;
        case 0x3c8:
            dacWriteIndex = data;
            dacSubIndex = 0;
            dacState = 0;
            break;
        case 0x3c9:
            dacCache[dacSubIndex] = data;
            if (++dacSubIndex == 3) {
                for (int i = 0; i < 3; i++)
                    palette[((0xff & dacWriteIndex) * 3) + i] = dacCache[i];
                dacSubIndex = 0;
                dacWriteIndex++;
            }
            break;
        case 0x3ce:
            graphicsRegisterIndex = data & 0x0f;
            break;
        case 0x3cf:
            graphicsRegister[graphicsRegisterIndex] = data & graphicsRegisterMask[graphicsRegisterIndex];
            break;
        }
    }

    private final int vgaIOPortReadByte(int address)
    {
        if ((address >= 0x3b0 && address <= 0x3bf && ((miscellaneousOutputRegister & MOR_COLOR_EMULATION) != 0)) ||
            (address >= 0x3d0 && address <= 0x3df && ((miscellaneousOutputRegister & MOR_COLOR_EMULATION) == 0)))
            return 0xff;

        switch (address) {
        case 0x3c0:
            if (!attributeRegisterFlipFlop) {
                return attributeRegisterIndex;
            } else {
                return 0;
            }
        case 0x3c1:
            int index = attributeRegisterIndex & 0x1f;
            if (index < 21) {
                return attributeRegister[index];
            } else {
                return 0;
            }
        case 0x3c2:
            return st00;
        case 0x3c4:
            return sequencerRegisterIndex;
        case 0x3c5:
            return sequencerRegister[sequencerRegisterIndex];
        case 0x3c7:
            return dacState;
        case 0x3c8:
            return dacWriteIndex;
        case 0x3c9:
            int val = palette[dacReadIndex * 3 + dacSubIndex];
            if (++dacSubIndex == 3) {
                dacSubIndex = 0;
                dacReadIndex++;
            }
            return val;
        case 0x3ca:
            return featureControlRegister;
        case 0x3cc:
            return miscellaneousOutputRegister;
        case 0x3ce:
            return graphicsRegisterIndex;
        case 0x3cf:
            return graphicsRegister[graphicsRegisterIndex];
        case 0x3b4:
        case 0x3d4:
            return crtRegisterIndex;
        case 0x3b5:
        case 0x3d5:
            return crtRegister[crtRegisterIndex];
        case 0x3ba:
        case 0x3da:
            attributeRegisterFlipFlop = false;
            if (!retracing) {
                st01 &= ~ST01_V_RETRACE; //claim we are not in vertical retrace (in the process of screen refresh)
                st01 &= ~ST01_DISP_ENABLE; //is set when in h/v retrace (i.e. if e-beam is off, but we claim always on)
            } else {
                st01 ^= (ST01_V_RETRACE | ST01_DISP_ENABLE); //if not updating toggle to fool polling in some vga code
            }
            return st01;
        default:
            return 0x00;
        }
    }

    private final void vbeIOPortWriteIndex(int data)
    {
        vbeIndex = data;
    }

    private final void vbeIOPortWriteData(int data)
    {
        if (vbeIndex <= VBE_DISPI_INDEX_NB) {
            switch(vbeIndex) {
            case VBE_DISPI_INDEX_ID:
                if (data == VBE_DISPI_ID0 || data == VBE_DISPI_ID1 || data == VBE_DISPI_ID2)
                    vbeRegs[vbeIndex] = data;
                break;
            case VBE_DISPI_INDEX_XRES:
                if ((data <= VBE_DISPI_MAX_XRES) && ((data & 7) == 0))
                    vbeRegs[vbeIndex] = data;
                break;
            case VBE_DISPI_INDEX_YRES:
                if (data <= VBE_DISPI_MAX_YRES)
                    vbeRegs[vbeIndex] = data;
                break;
            case VBE_DISPI_INDEX_BPP:
                if (data == 0)
                    data = 8;
                if (data == 4 || data == 8 || data == 15 ||
                    data == 16 || data == 24 || data == 32) {
                    vbeRegs[vbeIndex] = data;
                }
                break;
            case VBE_DISPI_INDEX_BANK:
                data &= vbeBankMask;
                vbeRegs[vbeIndex] = data;
                bankOffset = data << 16;
                break;
            case VBE_DISPI_INDEX_ENABLE:
                if ((data & VBE_DISPI_ENABLED) != 0) {
                    vbeRegs[VBE_DISPI_INDEX_VIRT_WIDTH] = vbeRegs[VBE_DISPI_INDEX_XRES];
                    vbeRegs[VBE_DISPI_INDEX_VIRT_HEIGHT] = vbeRegs[VBE_DISPI_INDEX_YRES];
                    vbeRegs[VBE_DISPI_INDEX_X_OFFSET] = 0;
                    vbeRegs[VBE_DISPI_INDEX_Y_OFFSET] = 0;

                    if (vbeRegs[VBE_DISPI_INDEX_BPP] == 4)
                        vbeLineOffset = vbeRegs[VBE_DISPI_INDEX_XRES] >>> 1;
                    else
                        vbeLineOffset = vbeRegs[VBE_DISPI_INDEX_XRES] * ((vbeRegs[VBE_DISPI_INDEX_BPP] + 7) >>> 3);

                    vbeStartAddress = 0;

                    /* clear the screen (should be done in BIOS) */
                    if ((data & VBE_DISPI_NOCLEARMEM) == 0)
                    {
                        int limit = vbeRegs[VBE_DISPI_INDEX_YRES] * vbeLineOffset;
                        for (int i=0; i<limit; i++)
                            ioRegion.setByte(i, (byte) 0);
                    }

                    /* we initialise the VGA graphic mode */
                    /* (should be done in BIOS) */
                    /* graphic mode + memory map 1 */
                    graphicsRegister[GR_INDEX_MISC] = (graphicsRegister[GR_INDEX_MISC] & ~0x0c) | 0x05;
                    crtRegister[CR_INDEX_CRTC_MODE_CONTROL] |= 0x3; /* no CGA modes */
                    crtRegister[CR_INDEX_OFFSET] = (vbeLineOffset >>> 3);
                    /* width */
                    crtRegister[CR_INDEX_HORZ_DISPLAY_END] = (vbeRegs[VBE_DISPI_INDEX_XRES] >>> 3) - 1;
                    /* height */
                    int h = vbeRegs[VBE_DISPI_INDEX_YRES] - 1;
                    crtRegister[CR_INDEX_VERT_DISPLAY_END] = h;
                    crtRegister[CR_INDEX_OVERFLOW] = (crtRegister[CR_INDEX_OVERFLOW] & ~0x42) | ((h >>> 7) & 0x02) | ((h >>> 3) & 0x40);
                    /* line compare to 1023 */
                    crtRegister[CR_INDEX_LINE_COMPARE] = 0xff;
                    crtRegister[CR_INDEX_OVERFLOW] |= 0x10;
                    crtRegister[CR_INDEX_MAX_SCANLINE] |= 0x40;

                    int shiftControl;
                    if (vbeRegs[VBE_DISPI_INDEX_BPP] == 4) {
                        shiftControl = 0;
                        sequencerRegister[SR_INDEX_CLOCKING_MODE] &= ~0x8; /* no double line */
                    } else {
                        shiftControl = 2;
                        sequencerRegister[SR_INDEX_SEQ_MEMORY_MODE] |= 0x08; /* set chain 4 mode */
                        sequencerRegister[SR_INDEX_MAP_MASK] |= 0x0f; /* activate all planes */
                    }
                    graphicsRegister[GR_INDEX_GRAPHICS_MODE] = (graphicsRegister[GR_INDEX_GRAPHICS_MODE] & ~0x60) | (shiftControl << 5);
                    crtRegister[CR_INDEX_MAX_SCANLINE] &= ~0x9f; /* no double scan */
                } else {
                    /* XXX: the bios should do that */
                    bankOffset = 0;
                }
                vbeRegs[vbeIndex] = data;
                break;
            case VBE_DISPI_INDEX_VIRT_WIDTH:
                {
                    if (data < vbeRegs[VBE_DISPI_INDEX_XRES])
                        return;
                    int w = data;
                    int lineOffset;
                    if (vbeRegs[VBE_DISPI_INDEX_BPP] == 4) {
                        lineOffset = data >>> 1;
                    } else {
                        lineOffset = data * ((vbeRegs[VBE_DISPI_INDEX_BPP] + 7) >>> 3);
                    }
                    int h = VGA_RAM_SIZE / lineOffset;
                    /* XXX: support wierd bochs semantics ? */
                    if (h < vbeRegs[VBE_DISPI_INDEX_YRES])
                        return;
                    vbeRegs[VBE_DISPI_INDEX_VIRT_WIDTH] = w;
                    vbeRegs[VBE_DISPI_INDEX_VIRT_HEIGHT] = h;
                    vbeLineOffset = lineOffset;
                }
                break;
            case VBE_DISPI_INDEX_X_OFFSET:
            case VBE_DISPI_INDEX_Y_OFFSET:
                {
                    vbeRegs[vbeIndex] = data;
                    vbeStartAddress = vbeLineOffset * vbeRegs[VBE_DISPI_INDEX_Y_OFFSET];
                    int x = vbeRegs[VBE_DISPI_INDEX_X_OFFSET];
                    if (vbeRegs[VBE_DISPI_INDEX_BPP] == 4) {
                        vbeStartAddress += x >>> 1;
                    } else {
                        vbeStartAddress += x * ((vbeRegs[VBE_DISPI_INDEX_BPP] + 7) >>> 3);
                    }
                    vbeStartAddress >>>= 2;
                }
                break;
            default:
                break;
            }
        }
    }

    private final int vbeIOPortReadIndex()
    {
        return vbeIndex;
    }

    private final int vbeIOPortReadData()
    {
        if (vbeIndex <= VBE_DISPI_INDEX_NB) {
            return vbeRegs[vbeIndex];
        } else {
            return 0;
        }
    }

    private final void internalReset()
    {
        latch = 0;
        sequencerRegisterIndex = graphicsRegisterIndex = attributeRegisterIndex = crtRegisterIndex = 0;
            attributeRegisterFlipFlop = false;
        miscellaneousOutputRegister = 0;
        featureControlRegister = 0;
        st00 = st01 = 0; // status 0 and 1
        dacState = dacSubIndex = dacReadIndex = dacWriteIndex = 0;
        shiftControl = doubleScan = 0;
        bankOffset = 0;
        vbeIndex = 0;
        vbeStartAddress = 0;
        vbeLineOffset = 0;
        vbeBankMask = 0;
        graphicMode = 0;
        lineOffset = 0;
        lineCompare = 0;
        startAddress = 0;
        planeUpdated = 0;
        lastCW = lastCH = 0;
        lastWidth = lastHeight = 0;
        lastScreenWidth = lastScreenHeight = 0;
        cursorStart = cursorEnd = 0;
        cursorOffset = 0;
//         for (int i=0; i<lastPalette.length; i++)
//             lastPalette[i] = new int[256];

        ByteBuffer.fillIntArray(lastChar, 0);
        ByteBuffer.fillIntArray(fontOffset, 0);
        ByteBuffer.fillIntArray(vbeRegs, 0);
        ByteBuffer.fillIntArray(dacCache, 0);
        ByteBuffer.fillIntArray(palette, 0);
        ByteBuffer.fillIntArray(sequencerRegister, 0);
        ByteBuffer.fillIntArray(graphicsRegister, 0);
        ByteBuffer.fillIntArray(attributeRegister, 0);
        ByteBuffer.fillIntArray(crtRegister, 0);

        graphicMode = -1;
    }

    public static org.jpc.SRDumpable loadSRVLMR(org.jpc.support.SRLoader input, Integer id) throws IOException
    {
        VGACard vc = (VGACard)(input.loadOuter());
        org.jpc.SRDumpable iElide = input.checkInnerElide(id);
        if(iElide != null)
            return iElide;
        org.jpc.SRDumpable x = vc.new VGALowMemoryRegion(input);
        input.endObject();
        return x;
    }

    public class VGALowMemoryRegion extends Memory
    {
        public void dumpSR(org.jpc.support.SRDumper output) throws IOException
        {
            if(output.dumped(this, "org.jpc.emulator.pci.peripheral.VGACard", "loadSRVLMR"))
                return;
            dumpSRPartial(output);
            output.endObject();
        }

        public void dumpSRPartial(org.jpc.support.SRDumper output) throws IOException
        {
            if(!output.dumpOuter(VGACard.this, this))
                return;
            super.dumpSRPartial(output);
        }

        public VGALowMemoryRegion()
        {
        }

        public VGALowMemoryRegion(org.jpc.support.SRLoader input) throws IOException
        {
            super(input);
        }

        public void dumpStatusPartial(org.jpc.support.StatusDumper output)
        {
            super.dumpStatusPartial(output);
            output.println("outer object <object #" + output.objectNumber(VGACard.this) + ">");
            VGACard.this.dumpStatus(output);
        }
 
        public void dumpStatus(org.jpc.support.StatusDumper output)
        {
            if(output.dumped(this))
                return;

            output.println("#" + output.objectNumber(this) + ": VGALowMemoryRegion:");
            dumpStatusPartial(output);
            output.endObject();
        }

        public boolean isCacheable() {return false;}
        public boolean isVolatile() {return true;}
        public void copyContentsInto(int address, byte[] buffer, int off, int len) {
            throw new IllegalStateException("copyContentsInto: Invalid Operation for VGA Card");
        }
        public void copyContentsFrom(int address, byte[] buffer, int off, int len) {
            throw new IllegalStateException("copyContentsFrom: Invalid Operation for VGA Card");
        }
        public long getSize()
        {
            return 0x20000;
        }

        public boolean isAllocated()
        {
            return false;
        }

        public byte getByte(int offset)
        {
            /* convert to VGA memory offset */
            int memoryMapMode = (graphicsRegister[GR_INDEX_MISC] >>> 2) & 3;
            offset &= 0x1ffff;
            switch (memoryMapMode) {
            case 0:
                break;
            case 1:
                if (offset >= 0x10000)
                    return (byte) 0xff;
                offset += bankOffset;
                break;
            case 2:
                offset -= 0x10000;
                if ((offset >= 0x8000) || (offset < 0))
                    return (byte) 0xff;
                break;
            default:
            case 3:
                offset -= 0x18000;
                if (offset < 0)
                    return (byte) 0xff;
                break;
            }

            if ((sequencerRegister[SR_INDEX_SEQ_MEMORY_MODE] & 0x08) != 0) {
                /* chain 4 mode : simplest access */
                //return vramPtr[address];
                return ioRegion.getByte(offset);
            } else if ((graphicsRegister[GR_INDEX_GRAPHICS_MODE] & 0x10) != 0) {
                /* odd/even mode (aka text mode mapping) */
                int plane = (graphicsRegister[GR_INDEX_READ_MAP_SELECT] & 2) | (offset & 1);
                return ioRegion.getByte(((offset & ~1) << 1) | plane);
            } else {
                /* standard VGA latched access */
                latch = ioRegion.getDoubleWord(4 * offset);

                if ((graphicsRegister[GR_INDEX_GRAPHICS_MODE] & 0x08) == 0) {
                    /* read mode 0 */
                    return (byte)(latch >>> (graphicsRegister[GR_INDEX_READ_MAP_SELECT] * 8));
                } else {
                    /* read mode 1 */
                    int ret = (latch ^ mask16[graphicsRegister[GR_INDEX_COLOR_COMPARE]])
                        & mask16[graphicsRegister[GR_INDEX_COLOR_DONT_CARE]];
                    ret |= ret >>> 16;
                    ret |= ret >>> 8;
                    return (byte)(~ret);
                }
            }
        }

        public short getWord(int offset)
        {
            int v = 0xFF & getByte(offset);
            v |= getByte(offset + 1) << 8;
            return (short) v;
        }

        public int getDoubleWord(int offset)
        {
            int v = 0xFF & getByte(offset);
            v |= (0xFF & getByte(offset + 1)) << 8;
            v |= (0xFF & getByte(offset + 2)) << 16;
            v |= (0xFF & getByte(offset + 3)) << 24;
            return v;
        }

        public long getQuadWord(int offset)
        {
            long v = 0xFFl & getByte(offset);
            v |= (0xFFl & getByte(offset + 1)) << 8;
            v |= (0xFFl & getByte(offset + 2)) << 16;
            v |= (0xFFl & getByte(offset + 3)) << 24;
            v |= (0xFFl & getByte(offset + 4)) << 32;
            v |= (0xFFl & getByte(offset + 5)) << 40;
            v |= (0xFFl & getByte(offset + 6)) << 48;
            v |= (0xFFl & getByte(offset + 7)) << 56;
            return v;
        }

        public long getLowerDoubleQuadWord(int offset)
        {
            return getQuadWord(offset);
        }

        public long getUpperDoubleQuadWord(int offset)
        {
            return getQuadWord(offset+8);
        }

        public void setByte(int offset, byte data)
        {
            /* convert to VGA memory offset */
            int memoryMapMode = (graphicsRegister[GR_INDEX_MISC] >>> 2) & 3;
            offset &= 0x1ffff;
            switch (memoryMapMode) {
            case 0:
                break;
            case 1:
                if (offset >= 0x10000)
                    return;
                offset += bankOffset;
                break;
            case 2:
                offset -= 0x10000;
                if ((offset >= 0x8000) || (offset < 0))
                    return;
                break;
            default:
            case 3:
                offset -= 0x18000;
                //should be (unsigned) if (offset >= 0x8000) but anding above "offset &= 0x1ffff;" means <=> the below
                if (offset < 0)
                    return;
                break;
            }

            if ((sequencerRegister[SR_INDEX_SEQ_MEMORY_MODE] & 0x08) != 0) {
                /* chain 4 mode : simplest access */
                int plane = offset & 3;
                int mask = 1 << plane;
                if ((sequencerRegister[SR_INDEX_MAP_MASK] & mask) != 0) {
                    ioRegion.setByte(offset, data);
                    planeUpdated |= mask; // only used to detect font change
                    //cpu_physical_memory_set_dirty
                }
            } else if ((graphicsRegister[GR_INDEX_GRAPHICS_MODE] & 0x10) != 0) {
                /* odd/even mode (aka text mode mapping) */
                int plane = (graphicsRegister[GR_INDEX_READ_MAP_SELECT] & 2) | (offset & 1);
                int mask = 1 << plane;
                if ((sequencerRegister[SR_INDEX_MAP_MASK] & mask) != 0) {
                    ioRegion.setByte(((offset & ~1) << 1) | plane, data);
                    planeUpdated |= mask; // only used to detect font change
                    //cpu_physical_memory_set_dirty
                }
            } else {
                /* standard VGA latched access */
                int bitMask = 0;
                int writeMode = graphicsRegister[GR_INDEX_GRAPHICS_MODE] & 3;
                int intData = 0xff & data;
                switch (writeMode) {
                default:
                case 0:
                    /* rotate */
                    int b = graphicsRegister[GR_INDEX_DATA_ROTATE] & 7;
                    intData |= intData << 8;
                    intData |= intData << 16;
                    intData = (intData >>> b) | (intData << -b);
                    //Integer.rotateRight(intData, b);

                    /* apply set/reset mask */
                    int setMask = mask16[graphicsRegister[GR_INDEX_ENABLE_SETRESET]];
                    intData = (intData & ~setMask) | (mask16[graphicsRegister[GR_INDEX_SETRESET]] & setMask);
                    bitMask = graphicsRegister[GR_INDEX_BITMASK];
                    break;
                case 1:
                    intData = latch;
                    int mask = sequencerRegister[SR_INDEX_MAP_MASK];
                    planeUpdated |= mask; // only used to detect font change
                    int writeMask = mask16[mask];
                    //check address being used here;
                    offset <<= 2;
                    ioRegion.setDoubleWord(offset, (ioRegion.getDoubleWord(offset) & ~writeMask) | (intData & writeMask));
                    return;
                case 2:
                    intData = mask16[intData & 0x0f];
                    bitMask = graphicsRegister[GR_INDEX_BITMASK];
                    break;
                case 3:
                    /* rotate */
                    b = graphicsRegister[GR_INDEX_DATA_ROTATE] & 7;
                    intData = ((intData >>> b) | (intData << (8-b)));
                    bitMask = graphicsRegister[GR_INDEX_BITMASK] & intData;
                    intData = mask16[graphicsRegister[GR_INDEX_SETRESET]];
                    break;
                }

                /* apply logical operation */
                int funcSelect = graphicsRegister[GR_INDEX_DATA_ROTATE] >>> 3;
                switch (funcSelect) {
                default:
                case 0:
                    /* nothing to do */
                    break;
                case 1:
                    /* and */
                    intData &= latch;
                    break;
                case 2:
                    /* or */
                    intData |= latch;
                    break;
                case 3:
                    /* xor */
                    intData ^= latch;
                    break;
                }

                /* apply bit mask */
                bitMask |= bitMask << 8;
                bitMask |= bitMask << 16;
                intData = (intData & bitMask) | (latch & ~bitMask);

                /* mask data according to sequencerRegister[SR_INDEX_MAP_MASK] */
                int mask = sequencerRegister[SR_INDEX_MAP_MASK];
                planeUpdated |= mask; // only used to detect font change
                int writeMask = mask16[mask];
                offset <<= 2;
                //check address being used here;
                ioRegion.setDoubleWord(offset, (ioRegion.getDoubleWord(offset) & ~writeMask) | (intData & writeMask));
            }
        }

        public void setWord(int offset, short data)
        {
            setByte(offset++, (byte)data);
            data >>>= 8;
            setByte(offset, (byte)data);
        }

        public void setDoubleWord(int offset, int data)
        {
            setByte(offset++, (byte)data);
            data >>>= 8;
            setByte(offset++, (byte)data);
            data >>>= 8;
            setByte(offset++, (byte)data);
            data >>>= 8;
            setByte(offset, (byte)data);
        }

        public void setQuadWord(int offset, long data)
        {
            setDoubleWord(offset, (int) data);
            setDoubleWord(offset+4, (int) (data >> 32));
        }

        public void setLowerDoubleQuadWord(int offset, long data)
        {
            setDoubleWord(offset, (int) data);
            setDoubleWord(offset+4, (int) (data >> 32));
        }

        public void setUpperDoubleQuadWord(int offset, long data)
        {
            offset += 8;
            setDoubleWord(offset, (int) data);
            setDoubleWord(offset+4, (int) (data >> 32));
        }

        public void clear()
        {
            internalReset();
        }

        public void clear(int start, int length)
        {
            clear();
        }

        public int execute(Processor cpu, int offset)
        {
            throw new IllegalStateException("Invalid Operation");
        }

        public CodeBlock decodeCodeBlockAt(Processor cpu, int offset)
        {
            throw new IllegalStateException("Invalid Operation");
        }

    }

    public static org.jpc.SRDumpable loadSRVRIR(org.jpc.support.SRLoader input, Integer id) throws IOException
    {
        org.jpc.SRDumpable x = new VGARAMIORegion(input);
        input.endObject();
        return x;
    }

    public static class VGARAMIORegion extends MemoryMappedIORegion
    {
        private byte[] buffer;
        private int startAddress;
        private boolean[] dirtyPages;
        private Magic magic2;

        public void dumpSR(org.jpc.support.SRDumper output) throws IOException
        {
            if(output.dumped(this, "org.jpc.emulator.pci.peripheral.VGACard", "loadSRVRIR"))
                return;
            dumpSRPartial(output);
            output.endObject();
        }

        public void dumpSRPartial(org.jpc.support.SRDumper output) throws IOException
        {
            super.dumpSRPartial(output);
            output.dumpArray(buffer);
            output.dumpInt(startAddress);
            output.dumpArray(dirtyPages);
        }

        public VGARAMIORegion(org.jpc.support.SRLoader input) throws IOException
        {
            super(input);
            buffer = input.loadArrayByte();
            startAddress = input.loadInt();
            dirtyPages = input.loadArrayBoolean();
        }

        public void dumpStatusPartial(org.jpc.support.StatusDumper output)
        {
            super.dumpStatusPartial(output);
            output.println("\tstartAddress " + startAddress);
            output.printArray(buffer, "buffer");
            output.printArray(dirtyPages, "dirtyPages");
        }
 
        public void dumpStatus(org.jpc.support.StatusDumper output)
        {
            if(output.dumped(this))
                return;

            output.println("#" + output.objectNumber(this) + ": VGARAMIORegion:");
            dumpStatusPartial(output);
            output.endObject();
        }

        public VGARAMIORegion()
        {
            magic2 = new Magic(Magic.VGA_RAM_IO_REGION_MAGIC_V1);
            //             buffer = new byte[VGA_RAM_SIZE];
            buffer = new byte[INIT_VGA_RAM_SIZE];
            dirtyPages = new boolean[(VGA_RAM_SIZE >>> PAGE_SHIFT) + 1];
            for (int i = 0; i < dirtyPages.length; i++)
                dirtyPages[i] = false;

            startAddress = -1;
        }

        public void dumpState(DataOutput output) throws IOException
        {
            magic2.dumpState(output);
            output.writeInt(startAddress);
            output.writeInt(buffer.length);
            output.write(buffer);
            output.writeInt(dirtyPages.length);
            for (int i=0; i< dirtyPages.length; i++)
                output.writeBoolean(dirtyPages[i]);
        }

        public void loadState(DataInput input) throws IOException
        {
            magic2.loadState(input);
            startAddress = input.readInt();
            int len = input.readInt();
            buffer = new byte[len];
            input.readFully(buffer,0,len);
            len = input.readInt();
            dirtyPages = new boolean[len];
            for (int i = 0; i < len; i++)
                dirtyPages[i] = input.readBoolean();
        }

        private void increaseVGARAMSize(int offset)
        {
            if ((offset < 0) || (offset >= VGA_RAM_SIZE))
                throw new ArrayIndexOutOfBoundsException("tried to access outside of memeory bounds");

            int newSize = buffer.length;
            while (newSize <= offset)
                newSize = newSize << 1;

            if (newSize > VGA_RAM_SIZE)
                newSize = VGA_RAM_SIZE;

            byte[] newBuf = new byte[newSize];
            System.arraycopy(buffer, 0, newBuf, 0, buffer.length);
            buffer = newBuf;
        }


        public void copyContentsInto(int address, byte[] buf, int off, int len)
        {
            System.out.println("address = " + address + ". offset = " + off + ". len = " + len + ". buffer.length = " + buffer.length);
            System.arraycopy(buffer, address, buf, off, len);
        }

        public void copyContentsFrom(int address, byte[] buf, int off, int len)
        {
            System.arraycopy(buf, off, buffer, address, len);
        }

        public void clear()
        {
            for (int i = 0; i < buffer.length; i++)
                buffer[i] = 0;

            for (int i = 0; i < dirtyPages.length; i++)
                dirtyPages[i] = false;
        }

        public void clear(int start, int length)
        {
            int limit = start + length;
            if (limit > getSize()) throw new ArrayIndexOutOfBoundsException("Attempt to clear outside of memory bounds");
            try {
                for (int i = start; i < limit; i++)
                    buffer[i] = 0;
            } catch (ArrayIndexOutOfBoundsException e) {}

            int pageStart = start >>> PAGE_SHIFT;
            int pageLimit = (limit - 1) >>> PAGE_SHIFT;
            for (int i = pageStart; i <= pageLimit; i++)
                dirtyPages[i] = true;
        }

        public boolean isCacheable()
        {
            return false;
        }

        public boolean isVolatile()
        {
            return false;
        }

        public boolean pageIsDirty(int i)
        {
            return dirtyPages[i];
        }

        public void cleanPage(int i)
        {
            dirtyPages[i] = false;
        }

        //IORegion Methods
        public int getAddress()
        {
            return startAddress;
        }

        public long getSize()
        {
            return VGA_RAM_SIZE;
        }

        public int getType()
        {
            return PCI_ADDRESS_SPACE_MEM_PREFETCH;
        }

        public int getRegionNumber()
        {
            return 0;
        }

        public void setAddress(int address)
        {
            this.startAddress = address;
        }

        public void setByte(int offset, byte data)
        {
            try
            {
                dirtyPages[offset >>> PAGE_SHIFT] = true;
                buffer[offset] = data;
            }
            catch (ArrayIndexOutOfBoundsException e)
            {
                increaseVGARAMSize(offset);
                setByte(offset, data);
            }
        }

        public byte getByte(int offset)
        {
            try
            {
                return buffer[offset];
            }
            catch (ArrayIndexOutOfBoundsException e)
            {
                increaseVGARAMSize(offset);
                return getByte(offset);
            }
        }

        public void setWord(int offset, short data)
        {
            try
            {
                buffer[offset] = (byte) data;
                dirtyPages[offset >>> PAGE_SHIFT] = true;
                offset++;
                buffer[offset] = (byte) (data >> 8);
                dirtyPages[offset >>> PAGE_SHIFT] = true;
            }
            catch (ArrayIndexOutOfBoundsException e)
            {
                increaseVGARAMSize(offset);
                setWord(offset, data);
            }
        }

        public short getWord(int offset)
        {
            try
            {
                int result = 0xFF & buffer[offset];
                offset++;
                result |= buffer[offset] << 8;
                return (short) result;
            }
            catch (ArrayIndexOutOfBoundsException e)
            {
                increaseVGARAMSize(offset);
                return getWord(offset);
            }
        }

        public void setDoubleWord(int offset, int data)
        {
            try
            {
                dirtyPages[offset >>> PAGE_SHIFT] = true;
                buffer[offset] = (byte) data;
                offset++;
                data >>= 8;
                buffer[offset] = (byte) (data);
                offset++;
                data >>= 8;
                buffer[offset] = (byte) (data);
                offset++;
                data >>= 8;
                buffer[offset] = (byte) (data);
                dirtyPages[offset >>> PAGE_SHIFT] = true;
            }
            catch (ArrayIndexOutOfBoundsException e)
            {
                increaseVGARAMSize(offset);
                setDoubleWord(offset, data);
            }
        }

        public int getDoubleWord(int offset)
        {
            try
            {
                int result=  0xFF & buffer[offset];
                offset++;
                result |= (0xFF & buffer[offset]) << 8;
                offset++;
                result |= (0xFF & buffer[offset]) << 16;
                offset++;
                result |= (buffer[offset]) << 24;
                return result;
            }
            catch (ArrayIndexOutOfBoundsException e)
            {
                increaseVGARAMSize(offset);
                return getDoubleWord(offset);
            }
        }

        public String toString()
        {
            return "VGA RAM ByteArray["+getSize()+"]";
        }

        public int execute(Processor cpu, int offset)
        {
            throw new IllegalStateException("Invalid Operation");
        }

        public CodeBlock decodeCodeBlockAt(Processor cpu, int offset)
        {
            throw new IllegalStateException("Invalid Operation");
        }

    }

    //Public Methods Used By Output Device
    public final void updateDisplay(GraphicsDisplay device)
    {
        updateDisplay(device, false);
    }


    public final void updateDisplay(GraphicsDisplay device, boolean forceFullUpdate)
    {
        if (device == null)
            return;

        updatingScreen = true;

        boolean fullUpdate = false;
        if(forceFullUpdate)
            fullUpdate = true;
        int detGraphicMode;
        if ((attributeRegisterIndex & 0x20) == 0)
            detGraphicMode = GMODE_BLANK;
        else
            detGraphicMode = graphicsRegister[GR_INDEX_MISC] & 1;

        if (detGraphicMode != this.graphicMode)
        {
            this.graphicMode = detGraphicMode;
            fullUpdate = true;
        }

        switch(graphicMode)
        {
        case GMODE_TEXT:
            drawText(fullUpdate, device);
            break;
        case GMODE_GRAPH:
            drawGraphic(fullUpdate, device);
            break;
        case GMODE_BLANK:
        default:
            drawBlank(fullUpdate, device);
            break;
        }

        updatingScreen = false;
    }


    private final void drawText(boolean fullUpdate, GraphicsDisplay device)
    {
        boolean temp = updatePalette16();
        fullUpdate |= temp;
        int[] palette = lastPalette;

        /* compute font data address (in plane 2) */
        int v = this.sequencerRegister[SR_INDEX_CHAR_MAP_SELECT];

        int offset = (((v >>> 4) & 1) | ((v << 1) & 6)) * 8192 * 4 + 2;
        if (offset != this.fontOffset[0]) {
            this.fontOffset[0] = offset;
            fullUpdate = true;
        }


        offset = (((v >>> 5) & 1) | ((v >>> 1) & 6)) * 8192 * 4 + 2;
        if (offset != this.fontOffset[1]) {
            this.fontOffset[1] = offset;
            fullUpdate = true;
        }

        if ((this.planeUpdated & (1 << 2)) != 0) {
            /* if the plane 2 was modified since the last display, it
               indicates the font may have been modified */
            this.planeUpdated = 0;
            fullUpdate = true;
        }

        temp = updateBasicParameters();
        fullUpdate |= temp;

        int srcIndex = this.startAddress * 4;

        /* total width and height */
        int charHeight = (crtRegister[CR_INDEX_MAX_SCANLINE] & 0x1f) + 1;
        int charWidth = 8;
        if ((sequencerRegister[SR_INDEX_CLOCKING_MODE] & 0x01) == 0)
            charWidth = 9;
        if ((sequencerRegister[SR_INDEX_CLOCKING_MODE] & 0x08) != 0)
            charWidth = 16; /* NOTE: no 18 pixel wide */

        int width = crtRegister[CR_INDEX_HORZ_DISPLAY_END] + 1;
        int height;
        if (crtRegister[CR_INDEX_VERT_TOTAL] == 100) {
            /* ugly hack for CGA 160x100x16 */
            height = 100;
        } else {
            height = crtRegister[CR_INDEX_VERT_DISPLAY_END] | ((crtRegister[CR_INDEX_OVERFLOW] & 0x02) << 7) | ((crtRegister[CR_INDEX_OVERFLOW] & 0x40) << 3);
            height = (height + 1) / charHeight;
        }

        if ((height * width) > CH_ATTR_SIZE) {
            /* better than nothing: exit if transient size is too big */
            return;
        }

        if ((width != this.lastWidth) || (height != this.lastHeight) || (charWidth != this.lastCW) || (charHeight != this.lastCH)) {
            this.lastScreenWidth = width * charWidth;
            this.lastScreenHeight = height * charHeight;
            device.resizeDisplay(this.lastScreenWidth, this.lastScreenHeight);
            this.lastWidth = width;
            this.lastHeight = height;
            this.lastCH = charHeight;
            this.lastCW = charWidth;
            fullUpdate = true;
        }

        int curCursorOffset = ((crtRegister[CR_INDEX_CURSOR_LOC_HIGH] << 8) | crtRegister[CR_INDEX_CURSOR_LOC_LOW]) - this.startAddress;

        if ((curCursorOffset != this.cursorOffset) || (crtRegister[CR_INDEX_CURSOR_START] != this.cursorStart) || (crtRegister[CR_INDEX_CURSOR_END] != this.cursorEnd)) {
            /* if the cursor position changed, we updated the old and new
               chars */
            if ((this.cursorOffset < CH_ATTR_SIZE) && (this.cursorOffset >= 0))
                this.lastChar[this.cursorOffset] = -1;
            if ((curCursorOffset < CH_ATTR_SIZE) && (curCursorOffset >= 0))
                this.lastChar[curCursorOffset] = -1;

            this.cursorOffset = curCursorOffset;
            this.cursorStart = crtRegister[CR_INDEX_CURSOR_START];
            this.cursorEnd = crtRegister[CR_INDEX_CURSOR_END];
        }

        int cursorIndex = (this.startAddress + this.cursorOffset) * 4;
        int lastCharOffset = 0;

        switch (charWidth) {
        case 8:
            for (int charY = 0; charY < height; charY++) {
                int srcOffset = srcIndex;
                for (int charX = 0; charX < width; charX++) {
                    int charShort = 0xffff & ioRegion.getWord(srcOffset);
                    if (fullUpdate || (charShort != this.lastChar[lastCharOffset])) {
                        this.lastChar[lastCharOffset] = charShort;

                        int character = 0xff & charShort;
                        int characterAttribute = charShort >>> 8;

                        int glyphOffset = fontOffset[(characterAttribute >>> 3) & 1] + 32 * 4 * character;
                        int backgroundColor = palette[characterAttribute >>> 4];
                        int foregroundColor = palette[characterAttribute & 0xf];

                        drawGlyph8(device.getDisplayBuffer(), charY * charHeight * lastScreenWidth + charX * 8,
                                   lastScreenWidth, glyphOffset, charHeight, foregroundColor, backgroundColor);
                        device.dirtyDisplayRegion(charX * 8, charY * charHeight, 8, charHeight);

                        if ((srcOffset == cursorIndex) && ((crtRegister[CR_INDEX_CURSOR_START] & 0x20) == 0)) {
                            int lineStart = crtRegister[CR_INDEX_CURSOR_START] & 0x1f;
                            int lineLast = crtRegister[CR_INDEX_CURSOR_END] & 0x1f;
                            /* XXX: check that */
                            if (lineLast > charHeight - 1)
                                lineLast = charHeight - 1;

                            if ((lineLast >= lineStart) && (lineStart < charHeight)) {
                                int tempHeight = lineLast - lineStart + 1;
                                drawCursorGlyph8(device.getDisplayBuffer(), (charY * charHeight + lineStart) * lastScreenWidth + charX * 8,
                                                 lastScreenWidth, tempHeight, foregroundColor, backgroundColor);
                                device.dirtyDisplayRegion(charX * 8, charY * charHeight + lineStart, 8, tempHeight);
                            }
                        }
                    }
                    srcOffset += 4;
                    lastCharOffset++;
                }
                srcIndex += lineOffset;
            }
            return;
        case 9:
            for (int charY = 0; charY < height; charY++) {
                int srcOffset = srcIndex;
                for (int charX = 0; charX < width; charX++) {
                    int charShort = 0xffff & ioRegion.getWord(srcOffset);
                    if (fullUpdate || (charShort != this.lastChar[lastCharOffset])) {
                        this.lastChar[lastCharOffset] = charShort;

                        int character = 0xff & charShort;
                        int characterAttribute = charShort >>> 8;

                        int glyphOffset = fontOffset[(characterAttribute >>> 3) & 1] + 32 * 4 * character;
                        int backgroundColor = palette[characterAttribute >>> 4];
                        int foregroundColor = palette[characterAttribute & 0xf];

                        boolean dup9 = ((character >= 0xb0) && (character <= 0xdf) && ((attributeRegister[AR_INDEX_ATTR_MODE_CONTROL] & 0x04) != 0));
                        drawGlyph9(device.getDisplayBuffer(), charY * charHeight * lastScreenWidth + charX * 9, lastScreenWidth,
                                   glyphOffset, charHeight, foregroundColor, backgroundColor, dup9);
                        device.dirtyDisplayRegion(charX * 9, charY * charHeight, 9, charHeight);

                        if ((srcOffset == cursorIndex) &&((crtRegister[CR_INDEX_CURSOR_START] & 0x20) == 0)) {
                            int lineStart = crtRegister[CR_INDEX_CURSOR_START] & 0x1f;
                            int lineLast = crtRegister[CR_INDEX_CURSOR_END] & 0x1f;
                            /* XXX: check that */
                            if (lineLast > charHeight - 1)
                                lineLast = charHeight - 1;

                            if ((lineLast >= lineStart) && (lineStart < charHeight)) {
                                int tempHeight = lineLast - lineStart + 1;
                                drawCursorGlyph9(device.getDisplayBuffer(), (charY * charHeight + lineStart) * lastScreenWidth + charX * 9,
                                                 lastScreenWidth, tempHeight, foregroundColor, backgroundColor);
                                device.dirtyDisplayRegion(charX * 9, charY * charHeight + lineStart, 9, tempHeight);
                            }
                        }
                    }
                    srcOffset += 4;
                    lastCharOffset++;
                }
                srcIndex += lineOffset;
            }
            return;
        case 16:
            for (int charY = 0; charY < height; charY++) {
                int srcOffset = srcIndex;
                for (int charX = 0; charX < width; charX++) {
                    int charShort = 0xffff & ioRegion.getWord(srcOffset);
                    if (fullUpdate || (charShort != this.lastChar[lastCharOffset])) {
                        this.lastChar[lastCharOffset] = charShort;

                        int character = 0xff & charShort;
                        int characterAttribute = charShort >>> 8;

                        int glyphOffset = fontOffset[(characterAttribute >>> 3) & 1] + 32 * 4 * character;
                        int backgroundColor = palette[characterAttribute >>> 4];
                        int foregroundColor = palette[characterAttribute & 0xf];

                        drawGlyph16(device.getDisplayBuffer(), charY * charHeight * lastScreenWidth + charX * 16,
                                    lastScreenWidth, glyphOffset, charHeight, foregroundColor, backgroundColor);
                        device.dirtyDisplayRegion(charX * 16, charY * charHeight, 16, charHeight);

                        if ((srcOffset == cursorIndex) &&((crtRegister[CR_INDEX_CURSOR_START] & 0x20) == 0)) {
                            int lineStart = crtRegister[CR_INDEX_CURSOR_START] & 0x1f;
                            int lineLast = crtRegister[CR_INDEX_CURSOR_END] & 0x1f;
                            /* XXX: check that */
                            if (lineLast > charHeight - 1)
                                lineLast = charHeight - 1;

                            if ((lineLast >= lineStart) && (lineStart < charHeight)) {
                                int tempHeight = lineLast - lineStart + 1;
                                drawCursorGlyph16(device.getDisplayBuffer(), (charY * charHeight + lineStart) * lastScreenWidth + charX * 16,
                                                  lastScreenWidth, tempHeight, foregroundColor, backgroundColor);
                                device.dirtyDisplayRegion(charX * 16, charY * charHeight + lineStart, 16, tempHeight);
                            }
                        }
                    }
                    srcOffset += 4;
                    lastCharOffset++;
                }
                srcIndex += lineOffset;
            }
            return;
        default:
            System.err.println("Unknown Character Width: " + charWidth);
            return;
        }
    }

    abstract class GraphicsUpdater implements org.jpc.SRDumpable
    {
        int[] ex4;
        int[] ex2;
        private Magic magic2;

        public abstract void dumpSR(org.jpc.support.SRDumper output) throws IOException;

        public void dumpSRPartial(org.jpc.support.SRDumper output) throws IOException
        {
            if(!output.dumpOuter(VGACard.this, this))
                return;
            output.dumpArray(ex4);
            output.dumpArray(ex2);
        }

        public void dumpStatusPartial(org.jpc.support.StatusDumper output)
        {
            output.println("outer object <object #" + output.objectNumber(VGACard.this) + ">");
            VGACard.this.dumpStatus(output);
            output.printArray(ex4, "ex4");
            output.printArray(ex2, "ex2");
        }
 
        public void dumpStatus(org.jpc.support.StatusDumper output)
        {
            if(output.dumped(this))
                return;

            output.println("#" + output.objectNumber(this) + ": GraphicsUpdater:");
            dumpStatusPartial(output);
            output.endObject();
        }

        public GraphicsUpdater(org.jpc.support.SRLoader input) throws IOException
        {
            input.objectCreated(this);
            ex4 = input.loadArrayInt();
            ex2 = input.loadArrayInt();
        }

        GraphicsUpdater()
        {
            magic2 = new Magic(Magic.GRAPHICS_UPDATER_MAGIC_V1);
            ex2 = new int[expand2.length];
            System.arraycopy(expand2, 0, ex2, 0, ex2.length);
            ex4 = new int[expand4.length];
            System.arraycopy(expand4, 0, ex4, 0, ex4.length);
        }

        abstract int byteWidth(int width);

        abstract void drawLine(int[] dest, int offset, int width, int y, int dispWidth);

        public void dumpState(DataOutput output) throws IOException
        {
            magic2.dumpState(output);
            output.writeInt(ex4.length);
            for (int i=0; i< ex4.length; i++)
                output.writeInt(ex4[i]);
            output.writeInt(ex2.length);
            for (int i=0; i< ex2.length; i++)
                output.writeInt(ex2[i]);
        }

        public void loadState(DataInput input) throws IOException
        {
            magic2.loadState(input);
            int len = input.readInt();
            ex4 = new int[len];
            for (int i=0; i< len; i++)
                ex4[i] = input.readInt();
            len = input.readInt();
            ex2 = new int[len];
            for (int i=0; i< len; i++)
                ex2[i] = input.readInt();
        }

        void updateDisplay(GraphicsDisplay device, int width, int height, int dispWidth, boolean fullUpdate, int multiScan)
        {
            int multiRun = multiScan;
            int addr1 = 4 * startAddress;
             //int lineSize = width; // get the line size from the display device??

            // if the "cursor_invalidate" function pointer is not null, then call it here.
            //if (s->cursor_invalidate)
            //s->cursor_invalidate(s);

            int y1 = 0;
            boolean addrMunge1 = (crtRegister[CR_INDEX_CRTC_MODE_CONTROL] & 1) == 0;
            boolean addrMunge2 = (crtRegister[CR_INDEX_CRTC_MODE_CONTROL] & 2) == 0;
            boolean addrMunge = addrMunge1 || addrMunge2;
            int mask = (crtRegister[CR_INDEX_CRTC_MODE_CONTROL] & 3) ^ 3;

            int pageMin = Integer.MAX_VALUE;
            int pageMax = Integer.MIN_VALUE;

            for(int y = 0; y < height; y++)
            {
                boolean update = fullUpdate;
                int addr = addr1;

                if (addrMunge)
                {
                    if (addrMunge1)
                    {
                        /* CGA compatibility handling */
                        int shift = 14 + ((crtRegister[CR_INDEX_CRTC_MODE_CONTROL] >>> 6) & 1);
                        addr = (addr & ~(1 << shift)) | ((y1 & 1) << shift);
                    }

                    if (addrMunge2)
                        addr = (addr & ~0x8000) | ((y1 & 2) << 14);
                }

                int pageStart = addr >>> PAGE_SHIFT;
                int pageEnd = (addr + byteWidth(width) - 1) >>> PAGE_SHIFT;
                for (int i = pageStart; i <= pageEnd; i++) {
                    if (update |= ioRegion.pageIsDirty(i)) {
                        pageMin = Math.min(pageMin, pageStart);
                        pageMax = Math.max(pageMax, pageEnd);
                        drawLine(device.getDisplayBuffer(), addr, width, y, dispWidth);
                        device.dirtyDisplayRegion(0, y, dispWidth, 1);
                        // if the "cursor_draw_line" function pointer is not null, then call it here.
                        //if (s->cursor_draw_line)
                        //   s->cursor_draw_line(s, d, y);
                        break;
                    }
                }

                if (multiRun == 0) {
                    if ((y1 & mask) == mask)
                        addr1 += lineOffset;
                    y1++;
                    multiRun = multiScan;
                } else
                    multiRun--;

                /* line compare acts on the displayed lines */
               if (y == lineCompare)
                   addr1 = 0;
            }

            for (int i = pageMin; i <= pageMax; i++)
                ioRegion.cleanPage(i);
        }
    }

    public static org.jpc.SRDumpable loadSRDL2(org.jpc.support.SRLoader input, Integer id) throws IOException
    {
        VGACard vc = (VGACard)(input.loadOuter());
        org.jpc.SRDumpable iElide = input.checkInnerElide(id);
        if(iElide != null)
            return iElide;
        org.jpc.SRDumpable x = vc.new DrawLine2(input);
        input.endObject();
        return x;
    }

    public static org.jpc.SRDumpable loadSRDL4(org.jpc.support.SRLoader input, Integer id) throws IOException
    {
        VGACard vc = (VGACard)(input.loadOuter());
        org.jpc.SRDumpable iElide = input.checkInnerElide(id);
        if(iElide != null)
            return iElide;
        org.jpc.SRDumpable x = vc.new DrawLine4(input);
        input.endObject();
        return x;
    }

    public static org.jpc.SRDumpable loadSRDL8(org.jpc.support.SRLoader input, Integer id) throws IOException
    {
        VGACard vc = (VGACard)(input.loadOuter());
        org.jpc.SRDumpable iElide = input.checkInnerElide(id);
        if(iElide != null)
            return iElide;
        org.jpc.SRDumpable x = vc.new DrawLine8(input);
        input.endObject();
        return x;
    }

    public static org.jpc.SRDumpable loadSRDL15(org.jpc.support.SRLoader input, Integer id) throws IOException
    {
        VGACard vc = (VGACard)(input.loadOuter());
        org.jpc.SRDumpable iElide = input.checkInnerElide(id);
        if(iElide != null)
            return iElide;
        org.jpc.SRDumpable x = vc.new DrawLine15(input);
        input.endObject();
        return x;
    }

    public static org.jpc.SRDumpable loadSRDL16(org.jpc.support.SRLoader input, Integer id) throws IOException
    {
        VGACard vc = (VGACard)(input.loadOuter());
        org.jpc.SRDumpable iElide = input.checkInnerElide(id);
        if(iElide != null)
            return iElide;
        org.jpc.SRDumpable x = vc.new DrawLine16(input);
        input.endObject();
        return x;
    }

    public static org.jpc.SRDumpable loadSRDL24(org.jpc.support.SRLoader input, Integer id) throws IOException
    {
        VGACard vc = (VGACard)(input.loadOuter());
        org.jpc.SRDumpable iElide = input.checkInnerElide(id);
        if(iElide != null)
            return iElide;
        org.jpc.SRDumpable x = vc.new DrawLine24(input);
        input.endObject();
        return x;
    }

    public static org.jpc.SRDumpable loadSRDL32(org.jpc.support.SRLoader input, Integer id) throws IOException
    {
        VGACard vc = (VGACard)(input.loadOuter());
        org.jpc.SRDumpable iElide = input.checkInnerElide(id);
        if(iElide != null)
            return iElide;
        org.jpc.SRDumpable x = vc.new DrawLine32(input);
        input.endObject();
        return x;
    }

    public static org.jpc.SRDumpable loadSRDL2D2(org.jpc.support.SRLoader input, Integer id) throws IOException
    {
        VGACard vc = (VGACard)(input.loadOuter());
        org.jpc.SRDumpable iElide = input.checkInnerElide(id);
        if(iElide != null)
            return iElide;
        org.jpc.SRDumpable x = vc.new DrawLine2d2(input);
        input.endObject();
        return x;
    }

    public static org.jpc.SRDumpable loadSRDL4D2(org.jpc.support.SRLoader input, Integer id) throws IOException
    {
        VGACard vc = (VGACard)(input.loadOuter());
        org.jpc.SRDumpable iElide = input.checkInnerElide(id);
        if(iElide != null)
            return iElide;
        org.jpc.SRDumpable x = vc.new DrawLine4d2(input);
        input.endObject();
        return x;
    }

    public static org.jpc.SRDumpable loadSRDL8D2(org.jpc.support.SRLoader input, Integer id) throws IOException
    {
        VGACard vc = (VGACard)(input.loadOuter());
        org.jpc.SRDumpable iElide = input.checkInnerElide(id);
        if(iElide != null)
            return iElide;
        org.jpc.SRDumpable x = vc.new DrawLine8d2(input);
        input.endObject();
        return x;
    }


    class DrawLine2 extends GraphicsUpdater
    {
        public void dumpSR(org.jpc.support.SRDumper output) throws IOException
        {
            if(output.dumped(this, "org.jpc.emulator.pci.peripheral.VGACard", "loadSRDL2"))
                return;
            dumpSRPartial(output);
            output.endObject();
        }

        public void dumpSRPartial(org.jpc.support.SRDumper output) throws IOException
        {
            super.dumpSRPartial(output);
        }

        public void dumpStatusPartial(org.jpc.support.StatusDumper output)
        {
            super.dumpStatusPartial(output);
        }
 
        public DrawLine2(org.jpc.support.SRLoader input) throws IOException
        {
            super(input);
        }

        public DrawLine2()
        {
        }

        public void dumpStatus(org.jpc.support.StatusDumper output)
        {
            if(output.dumped(this))
                return;

            output.println("#" + output.objectNumber(this) + ": DrawLine2:");
            dumpStatusPartial(output);
            output.endObject();
        }

        int byteWidth(int width)
        {
            return (width / 2);
        }

        void drawLine(int[] dest, int offset, int width, int y, int dispWidth)
        {
            int index = y * dispWidth;

            int[] palette = lastPalette;
            int planeMask = mask16[attributeRegister[AR_INDEX_COLOR_PLANE_ENABLE] & 0xf];
            width >>>= 3;

            do {
                int data = ioRegion.getDoubleWord(offset);
                data &= planeMask;

                int v = ex2[data & 0xff];
                v |= ex2[(data >>> 16) & 0xff] << 2;
                dest[index++] = palette[v >>> 12];
                dest[index++] = palette[(v >>> 8) & 0xf];
                dest[index++] = palette[(v >>> 4) & 0xf];
                dest[index++] = palette[(v >>> 0) & 0xf];

                v = ex2[(data >>> 8) & 0xff];
                v |= ex2[(data >>> 24) & 0xff] << 2;
                dest[index++] = palette[v >>> 12];
                dest[index++] = palette[(v >>> 8) & 0xf];
                dest[index++] = palette[(v >>> 4) & 0xf];
                dest[index++] = palette[(v >>> 0) & 0xf];
                offset += 4;
            } while (--width != 0);
        }
    }

    class DrawLine2d2 extends GraphicsUpdater
    {
        public void dumpSR(org.jpc.support.SRDumper output) throws IOException
        {
            if(output.dumped(this, "org.jpc.emulator.pci.peripheral.VGACard", "loadSRDL2D2"))
                return;
            dumpSRPartial(output);
            output.endObject();
        }

        public void dumpSRPartial(org.jpc.support.SRDumper output) throws IOException
        {
            super.dumpSRPartial(output);
        }

        public void dumpStatusPartial(org.jpc.support.StatusDumper output)
        {
            super.dumpStatusPartial(output);
        }
 
        public void dumpStatus(org.jpc.support.StatusDumper output)
        {
            if(output.dumped(this))
                return;

            output.println("#" + output.objectNumber(this) + ": DrawLine2d2:");
            dumpStatusPartial(output);
            output.endObject();
        }

        public DrawLine2d2(org.jpc.support.SRLoader input) throws IOException
        {
            super(input);
        }

        public DrawLine2d2()
        {
        }

        int byteWidth(int width)
        {
            return (width/2);
        }

        void drawLine(int[] dest, int offset, int width, int y, int dispWidth)
        {
            int index = y * dispWidth;

            int[] palette = lastPalette;
            int planeMask = mask16[attributeRegister[AR_INDEX_COLOR_PLANE_ENABLE] & 0xf];
            width >>>= 3;

            do {
                int data = ioRegion.getDoubleWord(offset);
                data &= planeMask;

                int v = ex2[data & 0xff];
                v |= ex2[(data >>> 16) & 0xff] << 2;
                dest[index++] = dest[index++] = palette[v >>> 12];
                dest[index++] = dest[index++] = palette[(v >>> 8) & 0xf];
                dest[index++] = dest[index++] = palette[(v >>> 4) & 0xf];
                dest[index++] = dest[index++] = palette[(v >>> 0) & 0xf];

                v = ex2[(data >>> 8) & 0xff];
                v |= ex2[(data >>> 24) & 0xff] << 2;
                dest[index++] = dest[index++] = palette[v >>> 12];
                dest[index++] = dest[index++] = palette[(v >>> 8) & 0xf];
                dest[index++] = dest[index++] = palette[(v >>> 4) & 0xf];
                dest[index++] = dest[index++] = palette[(v >>> 0) & 0xf];
                offset += 4;
            } while (--width != 0);
        }
    }

    class DrawLine4 extends GraphicsUpdater
    {
        public void dumpSR(org.jpc.support.SRDumper output) throws IOException
        {
            if(output.dumped(this, "org.jpc.emulator.pci.peripheral.VGACard", "loadSRDL4"))
                return;
            dumpSRPartial(output);
            output.endObject();
        }

        public void dumpSRPartial(org.jpc.support.SRDumper output) throws IOException
        {
            super.dumpSRPartial(output);
        }

        public DrawLine4(org.jpc.support.SRLoader input) throws IOException
        {
            super(input);
        }

        public DrawLine4()
        {
        }

        public void dumpStatusPartial(org.jpc.support.StatusDumper output)
        {
            super.dumpStatusPartial(output);
        }
 
        public void dumpStatus(org.jpc.support.StatusDumper output)
        {
            if(output.dumped(this))
                return;

            output.println("#" + output.objectNumber(this) + ": DrawLine4:");
            dumpStatusPartial(output);
            output.endObject();
        }

        int byteWidth(int width)
        {
            return (width/2);
        }

        void drawLine(int[] dest, int offset, int width, int y, int dispWidth)
        {
            int index = y * dispWidth;

            int[] palette = lastPalette;
            int planeMask = mask16[attributeRegister[AR_INDEX_COLOR_PLANE_ENABLE] & 0xf];
            width >>>= 3;

            do {
                int data = ioRegion.getDoubleWord(offset) & planeMask;

                int v = ex4[data & 0xff];
                data >>>= 8;
                v |= ex4[data & 0xff] << 1;
                data >>>= 8;
                v |= ex4[data & 0xff] << 2;
                data >>>= 8;
                v |= ex4[data & 0xff] << 3;

                dest[index++] = palette[v >>> 28];
                dest[index++] = palette[(v >>> 24) & 0xF];
                dest[index++] = palette[(v >>> 20) & 0xF];
                dest[index++] = palette[(v >>> 16) & 0xF];
                dest[index++] = palette[(v >>> 12) & 0xF];
                dest[index++] = palette[(v >>> 8) & 0xF];
                dest[index++] = palette[(v >>> 4) & 0xF];
                dest[index++] = palette[(v >>> 0) & 0xF];
                offset += 4;
            } while (--width != 0);
        }
    }

    class DrawLine4d2 extends GraphicsUpdater
    {
        public void dumpSR(org.jpc.support.SRDumper output) throws IOException
        {
            if(output.dumped(this, "org.jpc.emulator.pci.peripheral.VGACard", "loadSRDL4D2"))
                return;
            dumpSRPartial(output);
            output.endObject();
        }

        public void dumpSRPartial(org.jpc.support.SRDumper output) throws IOException
        {
            super.dumpSRPartial(output);
        }

        public DrawLine4d2(org.jpc.support.SRLoader input) throws IOException
        {
            super(input);
        }

        public DrawLine4d2()
        {
        }

        public void dumpStatusPartial(org.jpc.support.StatusDumper output)
        {
            super.dumpStatusPartial(output);
        }
 
        public void dumpStatus(org.jpc.support.StatusDumper output)
        {
            if(output.dumped(this))
                return;

            output.println("#" + output.objectNumber(this) + ": DrawLine4d2:");
            dumpStatusPartial(output);
            output.endObject();
        }

        int byteWidth(int width)
        {
            return (width/2);
        }

        void drawLine(int[] dest, int offset, int width, int y, int dispWidth)
        {
            int index = y * dispWidth;

            int[] palette = lastPalette;
            int planeMask = mask16[attributeRegister[AR_INDEX_COLOR_PLANE_ENABLE] & 0xf];
            width >>>= 3;

            do {
                int data = ioRegion.getDoubleWord(offset);
                data &= planeMask;

                int v = ex4[data & 0xff];
                v |= ex4[(data >>> 8) & 0xff] << 1;
                v |= ex4[(data >>> 16) & 0xff] << 2;
                v |= ex4[(data >>> 24) & 0xff] << 3;

                dest[index++] = dest[index++] = palette[v >>> 28];
                dest[index++] = dest[index++] = palette[(v >>> 24) & 0xF];
                dest[index++] = dest[index++] = palette[(v >>> 20) & 0xF];
                dest[index++] = dest[index++] = palette[(v >>> 16) & 0xF];
                dest[index++] = dest[index++] = palette[(v >>> 12) & 0xF];
                dest[index++] = dest[index++] = palette[(v >>> 8) & 0xF];
                dest[index++] = dest[index++] = palette[(v >>> 4) & 0xF];
                dest[index++] = dest[index++] = palette[(v >>> 0) & 0xF];
                offset += 4;
            } while (--width != 0);
        }
    }

    class DrawLine8d2 extends GraphicsUpdater
    {
        public void dumpSR(org.jpc.support.SRDumper output) throws IOException
        {
            if(output.dumped(this, "org.jpc.emulator.pci.peripheral.VGACard", "loadSRDL8D2"))
            if(output.dumped(this))
                return;
            dumpSRPartial(output);
            output.endObject();
        }

        public void dumpSRPartial(org.jpc.support.SRDumper output) throws IOException
        {
            super.dumpSRPartial(output);
        }

        public DrawLine8d2(org.jpc.support.SRLoader input) throws IOException
        {
            super(input);
        }

        public DrawLine8d2()
        {
        }


        public void dumpStatusPartial(org.jpc.support.StatusDumper output)
        {
            super.dumpStatusPartial(output);
        }
 
        public void dumpStatus(org.jpc.support.StatusDumper output)
        {
            if(output.dumped(this))
                return;

            output.println("#" + output.objectNumber(this) + ": DrawLine8d2:");
            dumpStatusPartial(output);
            output.endObject();
        }

        int byteWidth(int width)
        {
            return (width/2);
        }

        void drawLine(int[] dest, int offset, int width, int y, int dispWidth)
        {
            int index = y * dispWidth;

            int[] palette = lastPalette;
            width >>>= 1;

            do
            {
                int val = palette[0xFF & ioRegion.getByte(offset++)];
                dest[index++] = val;
                dest[index++] = val;
                width--;
            }
            while (width != 0);
        }
    }

    class DrawLine8 extends GraphicsUpdater
    {
        public void dumpSR(org.jpc.support.SRDumper output) throws IOException
        {
            if(output.dumped(this, "org.jpc.emulator.pci.peripheral.VGACard", "loadSRDL8"))
                return;
            dumpSRPartial(output);
            output.endObject();
        }

        public void dumpSRPartial(org.jpc.support.SRDumper output) throws IOException
        {
            super.dumpSRPartial(output);
        }

        public DrawLine8(org.jpc.support.SRLoader input) throws IOException
        {
            super(input);
        }

        public DrawLine8()
        {
        }

        public void dumpStatusPartial(org.jpc.support.StatusDumper output)
        {
            super.dumpStatusPartial(output);
        }
 
        public void dumpStatus(org.jpc.support.StatusDumper output)
        {
            if(output.dumped(this))
                return;

            output.println("#" + output.objectNumber(this) + ": DrawLine8:");
            dumpStatusPartial(output);
            output.endObject();
        }

        int byteWidth(int width)
        {
            return width;
        }

        void drawLine(int[] dest, int offset, int width, int y, int dispWidth)
        {
            int index = y * dispWidth;

            int[] palette = lastPalette;
            do
            {
                dest[index] = palette[0xFF & ioRegion.getByte(offset++)];
                index++;
                width--;
            }
            while (width != 0);
        }
    }

    class DrawLine15 extends GraphicsUpdater
    {
        public void dumpSR(org.jpc.support.SRDumper output) throws IOException
        {
            if(output.dumped(this, "org.jpc.emulator.pci.peripheral.VGACard", "loadSRDL15"))
                return;
            dumpSRPartial(output);
            output.endObject();
        }

        public void dumpSRPartial(org.jpc.support.SRDumper output) throws IOException
        {
            super.dumpSRPartial(output);
        }

        public DrawLine15(org.jpc.support.SRLoader input) throws IOException
        {
            super(input);
        }

        public DrawLine15()
        {
        }

        public void dumpStatusPartial(org.jpc.support.StatusDumper output)
        {
            super.dumpStatusPartial(output);
        }
 
        public void dumpStatus(org.jpc.support.StatusDumper output)
        {
            if(output.dumped(this))
                return;

            output.println("#" + output.objectNumber(this) + ": DrawLine15:");
            dumpStatusPartial(output);
            output.endObject();
        }

        int byteWidth(int width)
        {
            return width * 2;
        }

        void drawLine(int[] dest, int offset, int width, int y, int dispWidth)
        {
            int i = y * dispWidth;
            do {
                int v = 0xffff & ioRegion.getWord(offset);
                int r = (v >>> 7) & 0xf8;
                int g = (v >>> 2) & 0xf8;
                int b = (v << 3)  & 0xf8;
                dest[i] = VGACard.rgbToPixel(r, g, b);
                offset += 2;
                i++;
            } while (--width != 0);
        }
    }

    class DrawLine16 extends GraphicsUpdater
    {
        public void dumpSR(org.jpc.support.SRDumper output) throws IOException
        {
            if(output.dumped(this, "org.jpc.emulator.pci.peripheral.VGACard", "loadSRDL16"))
                return;
            dumpSRPartial(output);
            output.endObject();
        }

        public void dumpSRPartial(org.jpc.support.SRDumper output) throws IOException
        {
            super.dumpSRPartial(output);
        }

        public DrawLine16(org.jpc.support.SRLoader input) throws IOException
        {
            super(input);
        }

        public DrawLine16()
        {
        }

        public void dumpStatusPartial(org.jpc.support.StatusDumper output)
        {
            super.dumpStatusPartial(output);
        }
 
        public void dumpStatus(org.jpc.support.StatusDumper output)
        {
            if(output.dumped(this))
                return;

            output.println("#" + output.objectNumber(this) + ": DrawLine16:");
            dumpStatusPartial(output);
            output.endObject();
        }

        int byteWidth(int width)
        {
            return width * 2;
        }

        void drawLine(int[] dest, int offset, int width, int y, int dispWidth)
        {
            int i = y * dispWidth;
            do {
                int v = 0xffff & ioRegion.getWord(offset);
                int r = (v >>> 8) & 0xf8;
                int g = (v >>> 3) & 0xfc;
                int b = (v << 3)  & 0xf8;
                dest[i] = VGACard.rgbToPixel(r, g, b);
                offset += 2;
                i++;
            } while (--width != 0);
        }
    }

    class DrawLine24  extends GraphicsUpdater
    {
        public void dumpSR(org.jpc.support.SRDumper output) throws IOException
        {
            if(output.dumped(this, "org.jpc.emulator.pci.peripheral.VGACard", "loadSRDL24"))
                return;
            dumpSRPartial(output);
            output.endObject();
        }

        public void dumpSRPartial(org.jpc.support.SRDumper output) throws IOException
        {
            super.dumpSRPartial(output);
        }

        public DrawLine24(org.jpc.support.SRLoader input) throws IOException
        {
            super(input);
        }

        public DrawLine24()
        {
        }

        public void dumpStatusPartial(org.jpc.support.StatusDumper output)
        {
            super.dumpStatusPartial(output);
        }
 
        public void dumpStatus(org.jpc.support.StatusDumper output)
        {
            if(output.dumped(this))
                return;

            output.println("#" + output.objectNumber(this) + ": DrawLine24:");
            dumpStatusPartial(output);
            output.endObject();
        }

        int byteWidth(int width)
        {
            return width * 3;
        }

        void drawLine(int[] dest, int offset, int width, int y, int dispWidth)
        {
            int i = y * dispWidth;
            do {
                int b = 0xFF & ioRegion.getByte(offset++);
                int g = 0xFF & ioRegion.getByte(offset++);
                int r = 0xFF & ioRegion.getByte(offset++);

                dest[i++] = VGACard.rgbToPixel(r, g, b);
            } while (--width != 0);
        }
    }

    class DrawLine32 extends GraphicsUpdater
    {
        public void dumpSR(org.jpc.support.SRDumper output) throws IOException
        {
            if(output.dumped(this, "org.jpc.emulator.pci.peripheral.VGACard", "loadSRDL32"))
                return;
            dumpSRPartial(output);
            output.endObject();
        }

        public void dumpSRPartial(org.jpc.support.SRDumper output) throws IOException
        {
            super.dumpSRPartial(output);
        }

        public DrawLine32(org.jpc.support.SRLoader input) throws IOException
        {
            super(input);
        }

        public DrawLine32()
        {
        }

        public void dumpStatusPartial(org.jpc.support.StatusDumper output)
        {
            super.dumpStatusPartial(output);
        }
 
        public void dumpStatus(org.jpc.support.StatusDumper output)
        {
            if(output.dumped(this))
                return;

            output.println("#" + output.objectNumber(this) + ": DrawLine32:");
            dumpStatusPartial(output);
            output.endObject();
        }

        int byteWidth(int width)
        {
            return width * 4;
        }

        void drawLine(int[] dest, int offset, int width, int y, int dispWidth)
        {
            int i = y * dispWidth;
            do {
                int b = 0xff & ioRegion.getByte(offset++);
                int g = 0xff & ioRegion.getByte(offset++);
                int r = 0xff & ioRegion.getByte(offset++);
                offset++;

                dest[i++] = VGACard.rgbToPixel(r, g, b);
            } while (--width != 0);
        }
    }

    private final void drawGraphic(boolean fullUpdate, GraphicsDisplay device)
    {
        boolean temp = updateBasicParameters();
        fullUpdate |= temp;

        int width = (crtRegister[CR_INDEX_HORZ_DISPLAY_END] + 1) * 8;
        int height = (crtRegister[CR_INDEX_VERT_DISPLAY_END] | ((crtRegister[CR_INDEX_OVERFLOW] & 0x02) << 7) |  ((crtRegister[CR_INDEX_OVERFLOW] & 0x40) << 3)) + 1;

        int dispWidth = width;
        int shiftControlBuffer = (graphicsRegister[GR_INDEX_GRAPHICS_MODE] >>> 5) & 3;
        int doubleScanBuffer = crtRegister[CR_INDEX_MAX_SCANLINE] >>> 7;

        int multiScan;
        if (shiftControlBuffer != 1)
            multiScan = (((crtRegister[CR_INDEX_MAX_SCANLINE] & 0x1f) + 1) << doubleScanBuffer) - 1;
        else {
            /* in CGA modes, multi_scan is ignored */
            /* XXX: is it correct ? */
            multiScan = doubleScanBuffer;
        }

        if (shiftControlBuffer != shiftControl || doubleScanBuffer != doubleScan)
        {
            fullUpdate = true;
            this.shiftControl = shiftControlBuffer;
            this.doubleScan = doubleScanBuffer;
        }

        GraphicsUpdater graphicUpdater = null;
        if (shiftControl == 0)
        {
            temp = updatePalette16();
            fullUpdate |= temp;
            if ((sequencerRegister[SR_INDEX_CLOCKING_MODE] & 8) != 0 )
            {
                graphicUpdater = VGA_DRAW_LINE4D2;
                dispWidth <<= 1;
            }
            else
                graphicUpdater = VGA_DRAW_LINE4;
        }
        else if (shiftControl == 1)
        {
            temp = updatePalette16();
            fullUpdate |= temp;
            if ((sequencerRegister[SR_INDEX_CLOCKING_MODE] & 8) != 0)
            {
                graphicUpdater = VGA_DRAW_LINE2D2;
                dispWidth <<= 1;
            }
            else
                graphicUpdater = VGA_DRAW_LINE2;
        }
        else
        {
            int bpp = 0;
            if ((vbeRegs[VBE_DISPI_INDEX_ENABLE] & VBE_DISPI_ENABLED) != 0)
                bpp = vbeRegs[VBE_DISPI_INDEX_BPP];

            switch(bpp) {
            default:
            case 0:
                temp = updatePalette256();
                fullUpdate |= temp;
                graphicUpdater = VGA_DRAW_LINE8D2;
                break;
            case 8:
                temp = updatePalette256();
                fullUpdate |= temp;
                graphicUpdater = VGA_DRAW_LINE8;
                break;
            case 15:
                graphicUpdater = VGA_DRAW_LINE15;
                break;
            case 16:
                graphicUpdater = VGA_DRAW_LINE16;
                break;
            case 24:
                graphicUpdater = VGA_DRAW_LINE24;
                break;
            case 32:
                graphicUpdater = VGA_DRAW_LINE32;
                break;
            }
        }

        if ((dispWidth != lastWidth) || (height != lastHeight))
        {
            fullUpdate = true;
            lastScreenWidth = lastWidth = dispWidth;
            lastScreenHeight = lastHeight = height;
            device.resizeDisplay(lastScreenWidth, lastScreenHeight);
        }

        graphicUpdater.updateDisplay(device, width, height, dispWidth, fullUpdate, multiScan);
    }

    private final void drawBlank(boolean fullUpdate, GraphicsDisplay device)
    {
        if (!fullUpdate)
            return;
        if ((lastScreenWidth <= 0) || (lastScreenHeight <= 0))
            return;

        int[] rawBytes = device.getDisplayBuffer();
        int black = VGACard.rgbToPixel(0, 0, 0);
        for (int i=rawBytes.length-1; i>=0; i--)
            rawBytes[i] = black;

        device.dirtyDisplayRegion(0, 0, lastScreenWidth, lastScreenHeight);
    }

    static public int rgbToPixel(int red, int green, int blue)
    {
        return 0xFF000000 | ((0xFF & red) << 16) | ((0xFF & green) << 8) | (0xFF & blue);
    }

    private final boolean updatePalette16()
    {
        boolean fullUpdate = false;
        int[] palette = lastPalette;

        for (int colorIndex = AR_INDEX_PALLETE_MIN; colorIndex <= AR_INDEX_PALLETE_MAX; colorIndex++)
        {
            int v = attributeRegister[colorIndex];
            if ((attributeRegister[AR_INDEX_ATTR_MODE_CONTROL] & 0x80) != 0)
                v = ((attributeRegister[AR_INDEX_COLOR_SELECT] & 0xf) << 4) | (v & 0xf);
            else
                v = ((attributeRegister[AR_INDEX_COLOR_SELECT] & 0xc) << 4) | (v & 0x3f);

            v *= 3;
            int col = VGACard.rgbToPixel(c6to8(this.palette[v]), c6to8(this.palette[v+1]),
                c6to8(this.palette[v+2]));
            if (col != palette[colorIndex])
            {
                fullUpdate = true;
                palette[colorIndex] = col;
            }
        }
        return fullUpdate;
    }

    private final boolean updatePalette256()
    {
        boolean fullUpdate = false;
        int[] palette = lastPalette;

        for (int i = 0, v = 0; i < 256; i++, v+=3) {
            int col = VGACard.rgbToPixel(c6to8(this.palette[v]), c6to8(this.palette[v+1]),
                c6to8(this.palette[v+2]));
            if (col != palette[i]) {
                fullUpdate = true;
                palette[i] = col;
            }
        }
        return fullUpdate;
    }

    private final boolean updateBasicParameters()
    {
        int curStartAddress, curLineOffset;
        if ((vbeRegs[VBE_DISPI_INDEX_ENABLE] & VBE_DISPI_ENABLED) != 0) {
            curLineOffset = this.vbeLineOffset;
            curStartAddress = this.vbeStartAddress;
        } else {
            /* compute curLineOffset in bytes */
            curLineOffset = crtRegister[CR_INDEX_OFFSET];
            curLineOffset <<= 3;

            /* starting address */
            curStartAddress = crtRegister[CR_INDEX_START_ADDR_LOW] | (crtRegister[CR_INDEX_START_ADDR_HIGH] << 8);
        }

        /* line compare */
        int curLineCompare = crtRegister[CR_INDEX_LINE_COMPARE] | ((crtRegister[CR_INDEX_OVERFLOW] & 0x10) << 4) | ((crtRegister[CR_INDEX_MAX_SCANLINE] & 0x40) << 3);

        if ((curLineOffset != this.lineOffset) || (curStartAddress != this.startAddress) || (curLineCompare != this.lineCompare))
        {
            this.lineOffset = curLineOffset;
            this.startAddress = curStartAddress;
            this.lineCompare = curLineCompare;
            return true;
        }

        return false;
    }

    private static final int c6to8(int v)
    {
        v &= 0x3f;
        int b = v & 1;
        return (v << 2) | (b << 1) | b;
    }

    private final void drawGlyph8(int[] buffer, int startOffset, int scanSize, int glyphOffset, int charHeight, int foregroundColor, int backgroundColor)
    {
        int xorColor = backgroundColor ^ foregroundColor;
        scanSize -= 8;

        do {
            int fontData = ioRegion.getByte(glyphOffset);
            for (int i = 7; i >= 0; i--) {
                int pixel = ((-((fontData >>> i) & 1)) & xorColor) ^ backgroundColor;
                buffer[startOffset++] = pixel;
            }
            glyphOffset += 4;
            startOffset += scanSize;
        } while (--charHeight != 0);
    }

    private final void drawGlyph16(int[] buffer, int startOffset, int scanSize, int glyphOffset, int charHeight, int foregroundColor, int backgroundColor)
    {
        int xorColor = backgroundColor ^ foregroundColor;
        scanSize -= 16;

        do {
            int rawData = ioRegion.getByte(glyphOffset);
            int fontData = expand4to8[(rawData >>> 4) & 0x0f];
            for (int i = 7; i >= 0; i--) {
                int pixel = ((-((fontData >>> i) & 1)) & xorColor) ^ backgroundColor;
                buffer[startOffset++] = pixel;
            }
            fontData = expand4to8[rawData & 0x0f];
            for (int i = 7; i >= 0; i--) {
                int pixel = ((-((fontData >>> i) & 1)) & xorColor) ^ backgroundColor;
                buffer[startOffset++] = pixel;
            }
            glyphOffset += 4;
            startOffset += scanSize;
        } while (--charHeight != 0);
    }

    private final void drawGlyph9(int[] buffer, int startOffset, int scanSize, int glyphOffset, int charHeight, int foregroundColor, int backgroundColor, boolean dup9)
    {
        int xorColor = backgroundColor ^ foregroundColor;
        scanSize -= 9;

        if (dup9) {
            do {
                int fontData = ioRegion.getByte(glyphOffset);

                for (int i=7; i>=0; i--) {
                    int pixel = ((-((fontData >>> i) & 1)) & xorColor) ^ backgroundColor;
                    buffer[startOffset++] = pixel;
                }

                buffer[startOffset++] = buffer[startOffset-2];

                glyphOffset += 4;
                startOffset += scanSize;
            } while (--charHeight != 0);
        } else {
            do {
                int fontData = ioRegion.getByte(glyphOffset);

                for (int i=7; i>=0; i--) {
                    int pixel = ((-((fontData >>> i) & 1)) & xorColor) ^ backgroundColor;
                    buffer[startOffset++] = pixel;
                }

                buffer[startOffset++] = backgroundColor;

                glyphOffset += 4;
                startOffset += scanSize;
            } while (--charHeight != 0);
        }
    }

    private final void drawCursorGlyph8(int[] buffer, int startOffset, int scanSize, int charHeight, int foregroundColor, int backgroundColor)
    {
        int xorColor = backgroundColor ^ foregroundColor;
        int glyphOffset = 0;
        scanSize -= 8;

        do
        {
            int fontData = cursorGlyph[glyphOffset];
            for (int i = 7; i >= 0; i--)
            {
                int pixel = ((-((fontData >>> i) & 1)) & xorColor) ^ backgroundColor;
                buffer[startOffset++] = pixel;
            }
            glyphOffset += 4;
            startOffset += scanSize;
        }
        while (--charHeight != 0);
    }

    private final void drawCursorGlyph16(int[] buffer, int startOffset, int scanSize, int charHeight, int foregroundColor, int backgroundColor)
    {
        int glyphOffset = 0;
        int xorColor = backgroundColor ^ foregroundColor;
        scanSize -= 16;

        do
        {
            int rawData = cursorGlyph[glyphOffset];
            int fontData = expand4to8[(rawData >>> 4) & 0x0f];
            for (int i = 7; i >= 0; i--)
            {
                int pixel = ((-((fontData >>> i) & 1)) & xorColor) ^ backgroundColor;
                buffer[startOffset++] = pixel;
            }
            fontData = expand4to8[rawData & 0x0f];
            for (int i = 7; i >= 0; i--)
            {
                int pixel = ((-((fontData >>> i) & 1)) & xorColor) ^ backgroundColor;
                buffer[startOffset++] = pixel;
            }
            glyphOffset += 4;
            startOffset += scanSize;
        }
        while (--charHeight != 0);
    }

    private final void drawCursorGlyph9(int[] buffer, int startOffset, int scanSize, int charHeight, int foregroundColor, int backgroundColor)
    {
        int glyphOffset = 0;
        int xorColor = backgroundColor ^ foregroundColor;
        scanSize -= 9;

        do {
            int fontData = cursorGlyph[glyphOffset];
            for (int i=7; i>=0; i--) {
                int pixel = ((-((fontData >>> i) & 1)) & xorColor) ^ backgroundColor;
                buffer[startOffset++] = pixel;
            }
            buffer[startOffset++] = buffer[startOffset-2];
            glyphOffset++;
            startOffset += scanSize;
        } while (--charHeight != 0);
    }



    public boolean initialised()
    {
        return ioportRegistered && pciRegistered && memoryRegistered && (timeSource != null) && (traceTrap != null);
    }

    public void reset()
    {
        ioportRegistered = false;
        memoryRegistered = false;
        pciRegistered = false;

        assignDevFN(-1);
        setIRQIndex(16);

        putConfigByte(0x00, (byte)0x34); // dummy VGA (same as Bochs ID)
        putConfigByte(0x01, (byte)0x12);
        putConfigByte(0x02, (byte)0x11);
        putConfigByte(0x03, (byte)0x11);
        putConfigByte(0x0a, (byte)0x00); // VGA Controller
        putConfigByte(0x0b, (byte)0x03);
        putConfigByte(0x0e, (byte)0x00); // header_type

        sequencerRegister = new int[256];
        graphicsRegister = new int[256];
        attributeRegister = new int[256];
        crtRegister = new int[256];
        //invalidatedYTable = new int[VGA_MAX_HEIGHT / 32];

        dacCache = new int[3];
        palette = new int[768];

        ioRegion = new VGARAMIORegion();
        vbeRegs = new int[VBE_DISPI_INDEX_NB];

        fontOffset = new int[2];
        lastChar = new int[CH_ATTR_SIZE];

        this.internalReset();

        bankOffset = 0;

        vbeRegs[VBE_DISPI_INDEX_ID] = VBE_DISPI_ID0;
        vbeBankMask = ((VGA_RAM_SIZE >>> 16) - 1);

        super.reset();
    }

    public void timerCallback()
    {
        if(retracing) {
            //System.out.println("Ending VGA retrace.");
            retracing = false;
            nextTimerExpiry = nextTimerExpiry + TRACE_TIME;
            retraceTimer.setExpiry(nextTimerExpiry);
            traceTrap.doPotentialTrap(TraceTrap.TRACE_STOP_VRETRACE_END);
        } else {
            //System.out.println("Starting VGA retrace.");
            retracing = true;
            //Wait for monitor to draw.
            synchronized(this) {
                digitalOut.resetDirtyRegion();
                this.updateDisplay(digitalOut);
                notifyAll();
                while(true) {
                    try {
                        wait();
                        break;
                    } catch(InterruptedException e) {
                    }
                }
            }
            nextTimerExpiry = nextTimerExpiry + (FRAME_TIME - TRACE_TIME);
            retraceTimer.setExpiry(nextTimerExpiry);
            traceTrap.doPotentialTrap(TraceTrap.TRACE_STOP_VRETRACE_START);
        }
    }


    public boolean updated()
    {
        return ioportRegistered && pciRegistered && memoryRegistered && (timeSource != null) && (traceTrap != null);
    }

    public void updateComponent(HardwareComponent component)
    {
        if ((component instanceof PCIBus))
        {
            ((PCIBus)component).registerDevice(this);
            pciRegistered = true;
        }
        if ((component instanceof IOPortHandler))
        {
            ((IOPortHandler)component).registerIOPortCapable(this);
            ioportRegistered = true;
        }
        if ((component instanceof TraceTrap) && component.initialised())
        {
            traceTrap = (TraceTrap)component;
        }
        if ((component instanceof PhysicalAddressSpace) && component.updated())
        {
            ((PhysicalAddressSpace)component).mapMemoryRegion(lowIORegion, 0xa0000, 0x20000);
            memoryRegistered = true;
        }
    }

    public void acceptComponent(HardwareComponent component)
    {
        if ((component instanceof PCIBus) && component.initialised())
        {
            ((PCIBus)component).registerDevice(this);
            pciRegistered = true;
        }
        if ((component instanceof IOPortHandler) && component.initialised())
        {
            ((IOPortHandler)component).registerIOPortCapable(this);
            ioportRegistered = true;
        }
        if ((component instanceof PhysicalAddressSpace) && component.initialised())
        {
            ((PhysicalAddressSpace)component).mapMemoryRegion(lowIORegion, 0xa0000, 0x20000);
            memoryRegistered = true;
        }
        if ((component instanceof TraceTrap) && component.initialised())
        {
            traceTrap = (TraceTrap)component;
        }
        if ((component instanceof Clock) && component.initialised())
        {
            timeSource = (Clock)component;
            retraceTimer = timeSource.newTimer(this);
            System.out.println("Setting retrace timer expiry to " + nextTimerExpiry + ".");
            retraceTimer.setExpiry(nextTimerExpiry);
        }
    }

    public String toString()
    {
        return "VGA Card [Mode: " + lastScreenWidth + " x " + lastScreenHeight + "]";
    }
}
