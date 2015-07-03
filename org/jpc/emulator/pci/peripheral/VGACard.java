/*
    JPC-RR: A x86 PC Hardware Emulator
    Release 1

    Copyright (C) 2007-2009 Isis Innovation Limited
    Copyright (C) 2009-2011 H. Ilari Liusvaara
    Copyright (C) 2010 Joel Yliluoma

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

package org.jpc.emulator.pci.peripheral;

import org.jpc.emulator.StatusDumper;
import org.jpc.emulator.pci.*;
import org.jpc.emulator.motherboard.*;
import org.jpc.emulator.memory.*;
import org.jpc.emulator.processor.Processor;
import org.jpc.emulator.HardwareComponent;
import org.jpc.emulator.Clock;
import org.jpc.emulator.TimerResponsive;
import org.jpc.emulator.TraceTrap;
import org.jpc.emulator.VGADigitalOut;
import org.jpc.emulator.SRLoader;
import org.jpc.emulator.SRDumper;
import org.jpc.emulator.SRDumpable;
import org.jpc.emulator.DisplayController;

import java.io.*;

/**
 *
 * @author Chris Dennis
 * @author Rhys Newman
 * @author Ian Preston
 */
public class VGACard extends AbstractPCIDevice implements IOPortCapable, TimerResponsive,
    DisplayController
{
    //VGA_RAM_SIZE must be a power of two
    private static final int VGA_RAM_SIZE = 16 * 1024 * 1024;
    private static final int INIT_VGA_RAM_SIZE = 64 * 1024;
    private static final int PAGE_SHIFT = 12;

    private static final int[] expand4 = new int[256];
    static {
        for(int i = 0; i < expand4.length; i++) {
            int v = 0;
            for(int j = 0; j < 8; j++)
                v |= ((i >>> j) & 1) << (j * 4);
            expand4[i] = v;
        }
    }
    private static final int[] expand2 = new int[256];
    static {
        for(int i = 0; i < expand2.length; i++) {
            int v = 0;
            for(int j = 0; j < 4; j++)
                v |= ((i >>> (2 * j)) & 3) << (j * 4);
            expand2[i] = v;
        }
    }
    private static final int[] expand4to8 = new int[16];
    static {
        for(int i = 0; i < expand4to8.length; i++) {
            int v = 0;
            for(int j = 0; j < 4; j++) {
                int b = ((i >>> j) & 1);
                v |= b << (2 * j);
                v |= b << (2 * j + 1);
            }
            expand4to8[i] = v;
        }
    }

    private static final int VGA_MASTER_CLOCK_FREQ = 226575000;
    private static final int MOR_COLOR_EMULATION = 0x01;
    private static final int ST01_V_RETRACE = 0x08;
    private static final int ST01_DISP_ENABLE = 0x01;
    private static final int VBE_DISPI_MAX_XRES = 1600;
    private static final int VBE_DISPI_MAX_YRES = 1200;

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
    private static final int VBE_DISPI_MEMORY_SIZE = 0xa;
    private static final int VBE_DISPI_INDEX_NB = 0xa;

    private static final int VBE_DISPI_ID0 = 0xB0C0;
    private static final int VBE_DISPI_ID1 = 0xB0C1;
    private static final int VBE_DISPI_ID2 = 0xB0C2;
    private static final int VBE_DISPI_ID3 = 0xB0C3;
    private static final int VBE_DISPI_ID4 = 0xB0C4;
    private static final int VBE_DISPI_ID5 = 0xB0C5;

    private static final int VBE_DISPI_ENABLED = 0x01;
    private static final int VBE_DISPI_GETCAPS = 0x02;
    private static final int VBE_DISPI_8BITDAC = 0x20;
    //0x40 there is LFB enable, but its always enabled.
    private static final int VBE_DISPI_NOCLEARMEM = 0x80;

    private static final int GMODE_TEXT = 0;
    private static final int GMODE_GRAPH = 1;
    private static final int GMODE_BLANK = 2;

    private static final int CH_ATTR_SIZE = (160 * 100);

    private static final int GR_INDEX_SETRESET = 0x00;
    private static final int GR_INDEX_ENABLE_SETRESET = 0x01;
    private static final int GR_INDEX_COLOR_COMPARE = 0x02;
    private static final int GR_INDEX_DATA_ROTATE = 0x03;
    private static final int GR_INDEX_READ_MAP_SELECT = 0x04;
    private static final int GR_INDEX_GRAPHICS_MODE = 0x05;
    private static final int GR_INDEX_MISC = 0x06;
    private static final int GR_INDEX_COLOR_DONT_CARE = 0x07;
    private static final int GR_INDEX_BITMASK = 0x08;

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
    private static final int CR_INDEX_PRESET_ROW_SCAN = 0x08;
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

    private final GraphicsUpdater VGA_DRAW_LINE2;
    private final GraphicsUpdater VGA_DRAW_LINE2D2;
    private final GraphicsUpdater VGA_DRAW_LINE4;
    private final GraphicsUpdater VGA_DRAW_LINE4D2;
    private final GraphicsUpdater VGA_DRAW_LINE8D2;
    private final GraphicsUpdater VGA_DRAW_LINE8;
    private final GraphicsUpdater VGA_DRAW_LINE15;
    private final GraphicsUpdater VGA_DRAW_LINE16;
    private final GraphicsUpdater VGA_DRAW_LINE24;
    private final GraphicsUpdater VGA_DRAW_LINE32;

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
    private int pixelPanning;
    private int usePixelPanning;
    private int byteSkip;
    private int planeUpdated;
    private int lastCW, lastCH;
    private int lastWidth, lastHeight;
    private int lastScreenWidth, lastScreenHeight;
    private int cursorStart, cursorEnd;
    private int cursorOffset;
    private final int[] lastPalette;
    private int[] lastChar;
    private boolean updated;

    private TraceTrap traceTrap;

    private boolean ioportRegistered;
    private boolean pciRegistered;
    private boolean memoryRegistered;

    private boolean updatingScreen;
    private boolean vbeCapsMode;

    private VGARAMIORegion ioRegion;

    private VGALowMemoryRegion lowIORegion;

    /* Support constants for the old code,
     * where fps is fixed at 60.000 fps, 10% of which is in vrefresh */
    private static final long TRACE_TIME = 15000000;
    private static final long FRAME_TIME = 16666667;
    private static final long FRAME_TIME_ALT = 16666666;
    private static final long FRAME_ALT_MOD = 3;

    private Clock timeSource;
    private org.jpc.emulator.Timer retraceTimer;
    private VGADigitalOut outputDevice;
    private boolean retracing;

    private long whenFrameStarted;    //In VGA clocks.
    private long nextTimerExpiryVGA;  //In VGA clocks.
    private long nextTimerExpiry;
    private long frameNumber;
    private boolean lastVretraceStatus;
    private long vretraceRisingEdgesSeen;
    private long vgaScrollChanges;

    private boolean vgaDrawHackFlag;
    private boolean vgaScroll2HackFlag;
    public boolean SYSFLAG_VGAHRETRACE;

    public int SYSFLAG_VGATIMINGMETHOD;
    public int SYSFLAG_SVGATYPE;
    //These are in VGA clocks.
    private long draw_htotal, draw_vtotal;
    private long draw_hblkstart, draw_hblkend;
    private long draw_vrstart, draw_vrend, draw_vdend;
    private boolean returningFromVretrace;

    private boolean paletteDebuggingEnabled;  //Not saved.
    private boolean paletteLocked;  //Not saved.
    private PrintStream vgaDebugSaveIO;           //Not saved.

    public String STATUS_Frame_number;
    public String STATUS_Frame_rate;
    public String STATUS_Hardware_scrolls;
    public String STATUS_Retraces_seen;

    private void updateStatus()
    {
        STATUS_Frame_number = "" + frameNumber;
        STATUS_Frame_rate = getFramerate();
        STATUS_Hardware_scrolls = "" + vgaScrollChanges;
        STATUS_Retraces_seen = "" + vretraceRisingEdgesSeen;
    }

    private static long vgaClockToSystemClock(long vgaTicks)
    {
        long wholeSeconds = vgaTicks / VGA_MASTER_CLOCK_FREQ;
        long subSecondVGATicks = vgaTicks % VGA_MASTER_CLOCK_FREQ;
        long subSecondSysTicks = (subSecondVGATicks * 1000000000 + VGA_MASTER_CLOCK_FREQ / 2) / VGA_MASTER_CLOCK_FREQ;
        return 1000000000 * wholeSeconds + subSecondSysTicks;
    }

    private static long systemClockToVGAClock(long sysTicks)
    {
        long wholeSeconds = sysTicks / 1000000000;
        long subSecondSysTicks = sysTicks % 1000000000;
        long subSecondVGATicks = (subSecondSysTicks * VGA_MASTER_CLOCK_FREQ + 500000000) / 1000000000;
        return VGA_MASTER_CLOCK_FREQ * wholeSeconds + subSecondVGATicks;
    }


    public void dumpStatusPartial(StatusDumper output)
    {
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
        output.println("\tpixelPanning " + pixelPanning + " byteSkip " + byteSkip);
        output.println("\tusePixelPanning " + usePixelPanning + " SYSFLAG_SVGATYPE " + SYSFLAG_SVGATYPE);
        output.println("\tplaneUpdated " + planeUpdated + " lastCW " + lastCW + " lastCH " + lastCH);
        output.println("\tlastWidth " + lastWidth + " lastHeight " + lastHeight);
        output.println("\tlastScreenWidth " + lastScreenWidth + " lastScreenHeight " + lastScreenHeight);
        output.println("\tcursorStart " + cursorStart + " cursorEnd " + cursorEnd + " updated " + updated);
        output.println("\tcursorOffset " + cursorOffset + " ioportRegistered " + ioportRegistered);
        output.println("\tpciRegistered " + pciRegistered + " memoryRegistered " + memoryRegistered);
        output.println("\tupdatingScreen " + updatingScreen + " retracing " + retracing);
        output.println("\twhenFrameStarted " + whenFrameStarted + " vbeCapsMode " + vbeCapsMode);
        output.println("\tnextTimerExpiry " + nextTimerExpiry + " frameNumber "+ frameNumber);
        output.println("\tSYSFLAG_VGATIMINGMETHOD " + SYSFLAG_VGATIMINGMETHOD);
        output.println("\tnextTimerExpiryVGA " + nextTimerExpiryVGA);
        output.println("\tdraw_htotal " + draw_htotal + " draw_vtotal " + draw_vtotal);
        output.println("\tdraw_hblkstart " + draw_hblkstart + " draw_hblkend " + draw_hblkend);
        output.println("\tdraw_vrstart " + draw_vrstart + " draw_vrend " + draw_vrend + " draw_vdend " + draw_vdend);

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
        output.println("\toutputDevice <object #" + output.objectNumber(outputDevice) + ">"); if(outputDevice != null) outputDevice.dumpStatus(output);
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
        output.println("\tvgaDrawHackFlag " + vgaDrawHackFlag);
        output.println("\tvgaScroll2HackFlag " + vgaScroll2HackFlag);
        output.println("\tSYSFLAG_VGAHRETRACE " + SYSFLAG_VGAHRETRACE);
        output.println("\tlastVretraceStatus " + lastVretraceStatus);
        output.println("\tvretraceRisingEdgesSeen " + vretraceRisingEdgesSeen);
        output.println("\tvgaScrollChanges " + vgaScrollChanges);
    }

    public void dumpStatus(StatusDumper output)
    {
        if(output.dumped(this))
            return;

        output.println("#" + output.objectNumber(this) + ": VGACard:");
        dumpStatusPartial(output);
        output.endObject();
    }

    public void dumpSRPartial(SRDumper output) throws IOException
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
        output.dumpInt(pixelPanning);
        output.dumpInt(byteSkip);
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
        output.dumpObject(outputDevice);
        output.dumpLong(nextTimerExpiry);
        output.dumpLong(frameNumber);
        output.dumpBoolean(updated);
        output.dumpBoolean(vgaDrawHackFlag);
        output.dumpBoolean(SYSFLAG_VGAHRETRACE);
        output.dumpBoolean(vgaScroll2HackFlag);
        output.dumpInt(usePixelPanning);
        output.dumpLong(whenFrameStarted);
        output.dumpInt(SYSFLAG_VGATIMINGMETHOD);
        output.dumpLong(nextTimerExpiryVGA);
        output.dumpLong(draw_htotal);
        output.dumpLong(draw_vtotal);
        output.dumpLong(draw_hblkstart);
        output.dumpLong(draw_hblkend);
        output.dumpLong(draw_vrstart);
        output.dumpLong(draw_vrend);
        output.dumpLong(draw_vdend);
        output.dumpBoolean(returningFromVretrace);
        output.dumpBoolean(vbeCapsMode);
        output.dumpInt(SYSFLAG_SVGATYPE);
        output.dumpBoolean(lastVretraceStatus);
        output.dumpLong(vretraceRisingEdgesSeen);
        output.dumpLong(vgaScrollChanges);
    }

    public VGACard(SRLoader input) throws IOException
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
        pixelPanning = input.loadInt();
        byteSkip = input.loadInt();
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
        outputDevice = (VGADigitalOut)input.loadObject();
        nextTimerExpiry = input.loadLong();
        frameNumber = input.loadLong();
        updated = input.loadBoolean();
        vgaDrawHackFlag = false;
        vgaScroll2HackFlag = false;
        SYSFLAG_VGAHRETRACE = false;
        usePixelPanning = pixelPanning;
        whenFrameStarted = 0;
        //The timing method 1 values don't matter unless timing method 1 is actually used (and then those values)
        //are actually loaded.
        SYSFLAG_VGATIMINGMETHOD = 0;
        returningFromVretrace = false;
        updateStatus();
        if(input.objectEndsHere())
            return;
        vgaDrawHackFlag = input.loadBoolean();
        if(input.objectEndsHere())
            return;
        SYSFLAG_VGAHRETRACE = input.loadBoolean();
        if(input.objectEndsHere())
            return;
        vgaScroll2HackFlag = input.loadBoolean();
        if(input.objectEndsHere())
            return;
        usePixelPanning = input.loadInt();
        if(input.objectEndsHere())
            return;
        whenFrameStarted = input.loadLong();
        SYSFLAG_VGATIMINGMETHOD = input.loadInt();
        nextTimerExpiryVGA = input.loadLong();
        draw_htotal = input.loadLong();
        draw_vtotal = input.loadLong();
        draw_hblkstart = input.loadLong();
        draw_hblkend = input.loadLong();
        draw_vrstart = input.loadLong();
        draw_vrend = input.loadLong();
        draw_vdend = input.loadLong();
        returningFromVretrace = input.loadBoolean();
        updateStatus();
        if(input.objectEndsHere())
            return;
        vbeCapsMode = input.loadBoolean();
        SYSFLAG_SVGATYPE = input.loadInt();
        if(input.objectEndsHere())
            return;
        lastVretraceStatus = input.loadBoolean();
        vretraceRisingEdgesSeen = input.loadLong();
        vgaScrollChanges = input.loadLong();
        updateStatus();
    }

    public void DEBUGOPTION_VGA_palette_debugging(boolean _state)
    {
        paletteDebuggingEnabled = _state;
    }

    public void DEBUGOPTION_VGA_lock_palette(boolean _state)
    {
        paletteLocked = _state;
    }

    public void DEBUGOPTION_VGA_io_debugging(boolean _state)
    {
        if(_state)
            try {
                vgaDebugSaveIO = new PrintStream("VGA-io-debug.text");
            } catch(IOException e) {
                e.printStackTrace();
            }
        else if(vgaDebugSaveIO != null) {
            vgaDebugSaveIO.close();
            vgaDebugSaveIO = null;
        }
    }

    public String getCTRCDump()
    {
        String s = "";
        int dots = 9;
        if((sequencerRegister[SR_INDEX_CLOCKING_MODE] & 1) != 0)
            dots = 8;
        s = s + "Horizontal Total:          " + (crtRegister[0] + 5) * dots + "\n";
        s = s + "End Horizontal Display:    " + (crtRegister[1] + 1) * dots + "\n";
        s = s + "Start Horizontal Blanking: " + (crtRegister[2]) * dots + "\n";
        for(int i = 3; i < 25; i++)
            s = s + crtRegister[i] + " ";
        return s;
    }

    public String getFramerate()
    {
        long numerator = VGA_MASTER_CLOCK_FREQ;
        long denumerator = draw_vtotal;
        long gcd1 = numerator;
        long gcd2 = denumerator;
        if(SYSFLAG_VGATIMINGMETHOD == 0)
            return "60";
        while(gcd2 != 0) {
            long tmp = gcd1;
            gcd1 = gcd2;
            gcd2 = tmp % gcd2;
        };
        numerator /= gcd1;
        denumerator /= gcd1;
        if(denumerator != 1)
            return "" + numerator + "/" + denumerator;
        else
            return "" + numerator;
    }

    public void setVGADrawHack()
    {
        vgaDrawHackFlag = true;
    }

    public void setVGAScroll2Hack()
    {
        vgaScroll2HackFlag = true;
    }

    public VGADigitalOut getOutputDevice()
    {
        return outputDevice;
    }

    public VGACard()
    {
        ioportRegistered = false;
        memoryRegistered = false;
        pciRegistered = false;
        retracing = false;
        returningFromVretrace = false;
        timeSource = null;
        retraceTimer = null;
        nextTimerExpiry = TRACE_TIME;
        nextTimerExpiryVGA = 3398625;
        whenFrameStarted = 0;
        draw_vrstart = 0;
        draw_vrend   = 3398625;     //15ms.
        draw_vdend   = 0;
        draw_vtotal  = 3776250;     //1/60 s
        draw_htotal    = 7100;
        draw_hblkstart = 0;
        draw_hblkend   = 0;
        frameNumber = 0;
        SYSFLAG_VGATIMINGMETHOD = 0;

        VGA_DRAW_LINE2 = new DrawLine2(this);
        VGA_DRAW_LINE2D2 = new DrawLine2d2(this);
        VGA_DRAW_LINE4 = new DrawLine4(this);
        VGA_DRAW_LINE4D2 = new DrawLine4d2(this);
        VGA_DRAW_LINE8D2 = new DrawLine8d2(this);
        VGA_DRAW_LINE8 = new DrawLine8(this);
        VGA_DRAW_LINE15 = new DrawLine15(this);
        VGA_DRAW_LINE16 = new DrawLine16(this);
        VGA_DRAW_LINE24 = new DrawLine24(this);
        VGA_DRAW_LINE32 = new DrawLine32(this);

        assignDeviceFunctionNumber(-1);
        setIRQIndex(16);

        putConfigWord(PCI_CONFIG_VENDOR_ID, (short)0x1234); // Dummy
        putConfigWord(PCI_CONFIG_DEVICE_ID, (short)0x1111);
        putConfigWord(PCI_CONFIG_CLASS_DEVICE, (short)0x0300); // VGA Controller
        putConfigByte(PCI_CONFIG_HEADER, (byte)0x00); // header_type

        ioRegion = new VGARAMIORegion();
        lowIORegion = new VGALowMemoryRegion(this);

        outputDevice = new VGADigitalOut();

        lastPalette = new int[256];

        this.internalReset();

        bankOffset = 0;

        vbeRegs[VBE_DISPI_INDEX_ID] = VBE_DISPI_ID0;
        vbeBankMask = ((VGA_RAM_SIZE >>> 16) - 1);
        vbeRegs[VBE_DISPI_INDEX_XRES] = 1600;
        vbeRegs[VBE_DISPI_INDEX_YRES] = 1200;
        vbeRegs[VBE_DISPI_INDEX_BPP] = 32;
        updateStatus();
    }

    public void dirtyScreen()
    {
        outputDevice.dirtyDisplayRegion(0, 0, outputDevice.getWidth(), outputDevice.getHeight());
    }

    public void setOriginalDisplaySize() {
        outputDevice.resizeDisplay(lastScreenWidth, lastScreenHeight);
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

    private void ioDebug(String msg)
    {
        if(vgaDebugSaveIO == null)
            return;
        vgaDebugSaveIO.println(timeSource.getTime() + " " + msg);
    }

    public void ioPortWriteWord(int address, int data)
    {
        switch(address) {
        case 0x1ce:
        case 0xff80:
            ioDebug("IOWRITE_VESA " + Integer.toHexString(address) + " " + Integer.toHexString(data));
            vbeIOPortWriteIndex(data);
            break;
        case 0x1cf:
        case 0xff81:
            ioDebug("IOWRITE_VESA " + Integer.toHexString(address) + " " + Integer.toHexString(data));
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
        if((address >= 0x3b0 && address <= 0x3bf && ((miscellaneousOutputRegister & MOR_COLOR_EMULATION) != 0)) ||
            (address >= 0x3d0 && address <= 0x3df && ((miscellaneousOutputRegister & MOR_COLOR_EMULATION) == 0)))
            return;

        if((data & ~0xff) != 0)
            System.err.println("Error: VGA byte sized write data out of range???");

        ioDebug("IOWRITE_VGA " + Integer.toHexString(address) + " " + Integer.toHexString(data));

        switch(address) {
        case 0x3b4:
        case 0x3d4:
            crtRegisterIndex = data;
            break;
        case 0x3b5:
        case 0x3d5:
            if(crtRegisterIndex <= 7 && (crtRegister[CR_INDEX_VERT_RETRACE_END] & 0x80) != 0) {
                /* can always write bit 4 of CR_INDEX_OVERFLOW */
                if(crtRegisterIndex == CR_INDEX_OVERFLOW)
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
            if(!attributeRegisterFlipFlop) {
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
        case 0x3c7: {
            dacReadIndex = data;
            dacWriteIndex = (data + 1) & 0xFF;
            dacSubIndex = 0;
            dacState = 3;
            if(paletteDebuggingEnabled) {
                int palettetotal = 0;
                for(int a=0; a<768; ++a)
                    palettetotal += palette[a];
                System.err.println("DAC Read Index set to " + data + ", total palette power = " + palettetotal);
            }
            break;
        }
        case 0x3c8:
            dacWriteIndex = data;
            dacSubIndex = 0;
            dacState = 0;
            if(paletteDebuggingEnabled) {
                System.err.println("DAC Write Index set to " + data);
            }
            break;
        case 0x3c9:
            dacCache[dacSubIndex] = data;
            if(++dacSubIndex == 3) {
                for(int i = 0; i < 3; i++)
                    palette[((0xff & dacWriteIndex) * 3) + i] = dacCache[i];
                dacSubIndex = 0;
                dacWriteIndex++;
                if(paletteDebuggingEnabled && (dacWriteIndex&0xFF) == 0) {
                    int palettetotal = 0;
                    for(int a=0; a<768; ++a)
                        palettetotal += palette[a];
                    System.err.println("At palette end; total palette power = " + palettetotal);
                }
                dacWriteIndex &= 0xFF;
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

    private final void vretraceMeasured(boolean status)
    {
        if(status && !lastVretraceStatus) {
            vretraceRisingEdgesSeen++;
            updateStatus();
        }
        lastVretraceStatus = status;
    }

    public final long getVretraceEdgeCount()
    {
        return vretraceRisingEdgesSeen;
    }

    public final long getScrollCount()
    {
        return vgaScrollChanges;
    }

    private final int vgaIOPortReadByte(int address)
    {
        if((address >= 0x3b0 && address <= 0x3bf && ((miscellaneousOutputRegister & MOR_COLOR_EMULATION) != 0)) ||
            (address >= 0x3d0 && address <= 0x3df && ((miscellaneousOutputRegister & MOR_COLOR_EMULATION) == 0)))
            return 0xff;

        switch (address) {
        case 0x3c0:
            if(!attributeRegisterFlipFlop) {
                return attributeRegisterIndex;
            } else {
                return 0;
            }
        case 0x3c1:
            int index = attributeRegisterIndex & 0x1f;
            if(index < 21) {
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
            if(++dacSubIndex == 3) {
                dacSubIndex = 0;
                dacReadIndex = (dacReadIndex + 1) & 0xFF;
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

            if(SYSFLAG_VGATIMINGMETHOD == 1) {
                long timeInframe = systemClockToVGAClock(timeSource.getTime()) - whenFrameStarted;

                st01 &= ~(ST01_V_RETRACE | ST01_DISP_ENABLE);

                if(timeInframe >= draw_vrstart && timeInframe <= draw_vrend)
                    st01 |= ST01_V_RETRACE;
                if(timeInframe >= draw_vdend)
                    st01 |= ST01_DISP_ENABLE;
                else if(SYSFLAG_VGAHRETRACE) {
                    double timeinline = timeInframe % draw_htotal;
                    if(timeinline >= draw_hblkstart && timeinline <= draw_hblkend)
                        st01 |= ST01_DISP_ENABLE;
                }
            } else {
                if(retracing) {
                    st01 |= (ST01_V_RETRACE | ST01_DISP_ENABLE); //if not updating toggle to fool polling in some vga code
                } else {
                    int chunks = 10 * lastScreenHeight;
                    int chunk = 0;
                    if(chunks == 0) chunks = 1;
                    long frametime = timeSource.getTime() - (nextTimerExpiry - 16666666);

                    chunk = rescaleValue((int)frametime, (int)16666666, chunks);

                    st01 &= ~ST01_V_RETRACE; //claim we are not in vertical retrace (in the process of screen refresh)
                    if(!SYSFLAG_VGAHRETRACE || chunk % 10 < 9)
                        st01 &= ~ST01_DISP_ENABLE; //is set when in h/v retrace (i.e. if e-beam is off, but we claim always on)
                    else {
                        st01 |= ST01_DISP_ENABLE; //is set when in h/v retrace (i.e. if e-beam is off.
                    }
                }
            }
            vretraceMeasured((st01 & ST01_V_RETRACE) != 0);
            return st01;
        default:
            return 0x00;
        }
    }

    private final static int rescaleValue(int value, int origScale, int newScale)
    {
        long x = (long)value * (long)newScale;
        return (int)(x / origScale);
    }

    private final void vbeIOPortWriteIndex(int data)
    {
        vbeIndex = data;
    }

    private final void vbeIOPortWriteData(int data)
    {
        if(vbeIndex < VBE_DISPI_INDEX_NB) {
            switch(vbeIndex) {
            case VBE_DISPI_INDEX_ID:
                if(data == VBE_DISPI_ID0 || data == VBE_DISPI_ID1 || data == VBE_DISPI_ID2 || ((data == VBE_DISPI_ID3
                    || data == VBE_DISPI_ID4 || data == VBE_DISPI_ID5) && SYSFLAG_SVGATYPE == 1))
                    vbeRegs[vbeIndex] = data;
                break;
            case VBE_DISPI_INDEX_XRES:
                if((data <= VBE_DISPI_MAX_XRES) && ((data & 7) == 0))
                    vbeRegs[vbeIndex] = data;
                break;
            case VBE_DISPI_INDEX_YRES:
                if(data <= VBE_DISPI_MAX_YRES)
                    vbeRegs[vbeIndex] = data;
                break;
            case VBE_DISPI_INDEX_BPP:
                if(data == 0)
                    data = 8;
                if(data == 4 || data == 8 || data == 15 ||
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
                vbeCapsMode = ((data & VBE_DISPI_GETCAPS) != 0);
                if((data & VBE_DISPI_ENABLED) != 0) {
                    vbeRegs[VBE_DISPI_INDEX_VIRT_WIDTH] = vbeRegs[VBE_DISPI_INDEX_XRES];
                    vbeRegs[VBE_DISPI_INDEX_VIRT_HEIGHT] = vbeRegs[VBE_DISPI_INDEX_YRES];
                    vbeRegs[VBE_DISPI_INDEX_X_OFFSET] = 0;
                    vbeRegs[VBE_DISPI_INDEX_Y_OFFSET] = 0;

                    if(vbeRegs[VBE_DISPI_INDEX_BPP] == 4)
                        vbeLineOffset = vbeRegs[VBE_DISPI_INDEX_XRES] >>> 1;
                    else
                        vbeLineOffset = vbeRegs[VBE_DISPI_INDEX_XRES] * ((vbeRegs[VBE_DISPI_INDEX_BPP] + 7) >>> 3);

                    vbeStartAddress = 0;

                    /* clear the screen (should be done in BIOS) */
                    if((data & VBE_DISPI_NOCLEARMEM) == 0)
                    {
                        int limit = vbeRegs[VBE_DISPI_INDEX_YRES] * vbeLineOffset;
                        for(int i=0; i<limit; i++)
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
                    if(vbeRegs[VBE_DISPI_INDEX_BPP] == 4) {
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
                    if(data < vbeRegs[VBE_DISPI_INDEX_XRES])
                        return;
                    int w = data;
                    int lineOffset;
                    if(vbeRegs[VBE_DISPI_INDEX_BPP] == 4) {
                        lineOffset = data >>> 1;
                    } else {
                        lineOffset = data * ((vbeRegs[VBE_DISPI_INDEX_BPP] + 7) >>> 3);
                    }
                    int h = VGA_RAM_SIZE / lineOffset;
                    /* XXX: support wierd bochs semantics ? */
                    if(h < vbeRegs[VBE_DISPI_INDEX_YRES])
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
                    if(vbeRegs[VBE_DISPI_INDEX_BPP] == 4) {
                        vbeStartAddress += x >>> 1;
                    } else {
                        vbeStartAddress += x * ((vbeRegs[VBE_DISPI_INDEX_BPP] + 7) >>> 3);
                    }
                    vbeStartAddress >>>= 2;
                }
                break;
            default:
                System.err.println("Warning: Invalid VBE write mode: vbeIndex="  + vbeIndex);
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
        if(vbeCapsMode && vbeIndex == VBE_DISPI_INDEX_XRES)
            return VBE_DISPI_MAX_XRES;
        if(vbeCapsMode && vbeIndex == VBE_DISPI_INDEX_YRES)
            return VBE_DISPI_MAX_YRES;
        if(vbeCapsMode && vbeIndex == VBE_DISPI_INDEX_BPP)
            return 32;
        if(vbeIndex < VBE_DISPI_INDEX_NB)
            return vbeRegs[vbeIndex];
        else if(vbeIndex == VBE_DISPI_MEMORY_SIZE)
            return VGA_RAM_SIZE >>> 16;
        else
            return 0;
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
        pixelPanning = 0;
        usePixelPanning = 0;
        byteSkip = 0;
        planeUpdated = 0;
        lastCW = lastCH = 0;
        lastWidth = lastHeight = 0;
        lastScreenWidth = lastScreenHeight = 0;
        cursorStart = cursorEnd = 0;
        cursorOffset = 0;
//         for(int i=0; i<lastPalette.length; i++)
//             lastPalette[i] = new int[256];

        //invalidatedYTable = new int[VGA_MAX_HEIGHT / 32];
        lastChar = new int[CH_ATTR_SIZE];
        fontOffset = new int[2];
        vbeRegs = new int[VBE_DISPI_INDEX_NB];
        dacCache = new int[3];
        palette = new int[768];
        sequencerRegister = new int[256];
        graphicsRegister = new int[256];
        attributeRegister = new int[256];
        crtRegister = new int[256];

        graphicMode = -1;
    }

    public long getFrameNumber()
    {
        return frameNumber;
    }

    public int getWidth()
    {
        return lastScreenWidth;
    }

    public int getHeight()
    {
        return lastScreenHeight;
    }

    public static class VGALowMemoryRegion implements Memory
    {
        private VGACard upperBackref;

        public void dumpSRPartial(SRDumper output) throws IOException
        {
            output.dumpObject(upperBackref);
        }

        public VGALowMemoryRegion(VGACard backref)
        {
            upperBackref = backref;
        }

        public VGALowMemoryRegion(SRLoader input) throws IOException
        {
            input.objectCreated(this);
            upperBackref = (VGACard)input.loadObject();
        }

        public void dumpStatusPartial(StatusDumper output)
        {
            //super.dumpStatusPartial(output); <no superclass 20090704>
            output.println("\tupperBackref <object #" + output.objectNumber(upperBackref) + ">"); if(upperBackref != null) upperBackref.dumpStatus(output);
        }

        public void dumpStatus(StatusDumper output)
        {
            if(output.dumped(this))
                return;

            output.println("#" + output.objectNumber(this) + ": VGALowMemoryRegion:");
            dumpStatusPartial(output);
            output.endObject();
        }

        public void copyContentsIntoArray(int address, byte[] buffer, int off, int len) {
            System.err.println("Critical error: CopyContentsIntoArray not supported for low VGA memory.");
            throw new IllegalStateException("copyContentsInto: Invalid Operation for VGA Card");
        }
        public void copyArrayIntoContents(int address, byte[] buffer, int off, int len) {
            System.err.println("Critical error: CopyArrayIntoContents not supported for low VGA memory.");
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
            int memoryMapMode = (upperBackref.graphicsRegister[GR_INDEX_MISC] >>> 2) & 3;
            offset &= 0x1ffff;
            boolean fromGraphicsMemory = (offset < 65536);
            switch (memoryMapMode) {
            case 0:
                break;
            case 1:
                if(offset >= 0x10000)
                    return (byte) 0xff;
                offset += upperBackref.bankOffset;
                break;
            case 2:
                offset -= 0x10000;
                if((offset >= 0x8000) || (offset < 0))
                    return (byte) 0xff;
                break;
            default:
            case 3:
                offset -= 0x18000;
                if(offset < 0)
                    return (byte) 0xff;
                break;
            }

            //System.err.println("Read from VGA low memory address 0x" + Integer.toHexString(origOffset) + " -> 0x" + Integer.toHexString(offset) + " (mode " + Integer.toHexString(upperBackref.graphicsRegister[GR_INDEX_GRAPHICS_MODE]) + ").");

            boolean oddEven = ((upperBackref.graphicsRegister[GR_INDEX_GRAPHICS_MODE] & 0x10) != 0);
            if(fromGraphicsMemory)
                oddEven = false;   //Lock this out in graphics modes.

            if((upperBackref.sequencerRegister[SR_INDEX_SEQ_MEMORY_MODE] & 0x08) != 0) {
                /* chain 4 mode : simplest access */
                //return vramPtr[address];
                return upperBackref.ioRegion.getByte(offset);
            } else if(oddEven) {
                /* odd/even mode (aka text mode mapping) */
                int plane = (upperBackref.graphicsRegister[GR_INDEX_READ_MAP_SELECT] & 2) | (offset & 1);
                return upperBackref.ioRegion.getByte(((offset & ~1) << 1) | plane);
            } else {
                /* standard VGA latched access */
                upperBackref.latch = upperBackref.ioRegion.getDoubleWord(4 * offset);

                if((upperBackref.graphicsRegister[GR_INDEX_GRAPHICS_MODE] & 0x08) == 0) {
                    /* read mode 0 */
                    return (byte)(upperBackref.latch >>> (upperBackref.graphicsRegister[GR_INDEX_READ_MAP_SELECT] * 8));
                } else {
                    /* read mode 1 */
                    int ret = (upperBackref.latch ^ mask16[upperBackref.graphicsRegister[GR_INDEX_COLOR_COMPARE]])
                        & mask16[upperBackref.graphicsRegister[GR_INDEX_COLOR_DONT_CARE]];
                    ret |= ret >>> 16;
                    ret |= ret >>> 8;
                    return (byte)(~ret);
                }
            }
        }

        public short getWord(int offset)
        {
            int v = 0xFF & getByte(offset);
            v |= (0xFF & getByte(offset + 1)) << 8;
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
            upperBackref.ioDebug("LOWWRITE " + Integer.toHexString(offset) + " " + Integer.toHexString(data));
            int memoryMapMode = (upperBackref.graphicsRegister[GR_INDEX_MISC] >>> 2) & 3;
            offset &= 0x1ffff;
            boolean fromGraphicsMemory = (offset < 65536);
            switch (memoryMapMode) {
            case 0:
                break;
            case 1:
                if(offset >= 0x10000)
                    return;
                offset += upperBackref.bankOffset;
                break;
            case 2:
                offset -= 0x10000;
                if((offset >= 0x8000) || (offset < 0))
                    return;
                break;
            default:
            case 3:
                offset -= 0x18000;
                //should be (unsigned) if(offset >= 0x8000) but anding above "offset &= 0x1ffff;" means <=> the below
                if(offset < 0)
                    return;
                break;
            }

            boolean oddEven = ((upperBackref.graphicsRegister[GR_INDEX_GRAPHICS_MODE] & 0x10) != 0);
            if(fromGraphicsMemory)
                oddEven = false;   //Lock this out in graphics modes.

            if((upperBackref.sequencerRegister[SR_INDEX_SEQ_MEMORY_MODE] & 0x08) != 0) {
                /* chain 4 mode : simplest access */
                int plane = offset & 3;
                int mask = 1 << plane;
                if((upperBackref.sequencerRegister[SR_INDEX_MAP_MASK] & mask) != 0) {
                    upperBackref.ioRegion.setByte(offset, data);
                    upperBackref.planeUpdated |= mask; // only used to detect font change
                }
            } else if(oddEven) {
                /* odd/even mode (aka text mode mapping) */
                int plane = (upperBackref.graphicsRegister[GR_INDEX_READ_MAP_SELECT] & 2) | (offset & 1);
                int mask = 1 << plane;
                if((upperBackref.sequencerRegister[SR_INDEX_MAP_MASK] & mask) != 0) {
                    upperBackref.ioRegion.setByte(((offset & ~1) << 1) | plane, data);
                    upperBackref.planeUpdated |= mask; // only used to detect font change
                }
            } else {
                /* standard VGA latched access */
                int bitMask = 0;
                int writeMode = upperBackref.graphicsRegister[GR_INDEX_GRAPHICS_MODE] & 3;
                int intData = 0xff & data;
                switch (writeMode) {
                default:
                case 0:
                    /* rotate */
                    int b = upperBackref.graphicsRegister[GR_INDEX_DATA_ROTATE] & 7;
                    intData |= intData << 8;
                    intData |= intData << 16;
                    intData = (intData >>> b) | (intData << -b);
                    //Integer.rotateRight(intData, b);

                    /* apply set/reset mask */
                    int setMask = mask16[upperBackref.graphicsRegister[GR_INDEX_ENABLE_SETRESET]];
                    intData = (intData & ~setMask) | (mask16[upperBackref.graphicsRegister[GR_INDEX_SETRESET]] & setMask);
                    bitMask = upperBackref.graphicsRegister[GR_INDEX_BITMASK];
                    break;
                case 1:
                    intData = upperBackref.latch;
                    int mask = upperBackref.sequencerRegister[SR_INDEX_MAP_MASK];
                    upperBackref.planeUpdated |= mask; // only used to detect font change
                    int writeMask = mask16[mask];
                    //check address being used here;
                    offset <<= 2;
                    upperBackref.ioRegion.setDoubleWord(offset, (upperBackref.ioRegion.getDoubleWord(offset) &
                        ~writeMask) | (intData & writeMask));
                    return;
                case 2:
                    intData = mask16[intData & 0x0f];
                    bitMask = upperBackref.graphicsRegister[GR_INDEX_BITMASK];
                    break;
                case 3:
                    /* rotate */
                    b = upperBackref.graphicsRegister[GR_INDEX_DATA_ROTATE] & 7;
                    intData = ((intData >>> b) | (intData << (8-b)));
                    bitMask = upperBackref.graphicsRegister[GR_INDEX_BITMASK] & intData;
                    intData = mask16[upperBackref.graphicsRegister[GR_INDEX_SETRESET]];
                    break;
                }

                /* apply logical operation */
                int funcSelect = upperBackref.graphicsRegister[GR_INDEX_DATA_ROTATE] >>> 3;
                switch (funcSelect) {
                default:
                case 0:
                    /* nothing to do */
                    break;
                case 1:
                    /* and */
                    intData &= upperBackref.latch;
                    break;
                case 2:
                    /* or */
                    intData |= upperBackref.latch;
                    break;
                case 3:
                    /* xor */
                    intData ^= upperBackref.latch;
                    break;
                }

                /* apply bit mask */
                bitMask |= bitMask << 8;
                bitMask |= bitMask << 16;
                intData = (intData & bitMask) | (upperBackref.latch & ~bitMask);

                /* mask data according to sequencerRegister[SR_INDEX_MAP_MASK] */
                int mask = upperBackref.sequencerRegister[SR_INDEX_MAP_MASK];
                upperBackref.planeUpdated |= mask; // only used to detect font change
                int writeMask = mask16[mask];
                offset <<= 2;
                //check address being used here;
                upperBackref.ioRegion.setDoubleWord(offset, (upperBackref.ioRegion.getDoubleWord(offset) &
                    ~writeMask) | (intData & writeMask));
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
            upperBackref.internalReset();
        }

        public void clear(int start, int length)
        {
            clear();
        }

        public int executeReal(Processor cpu, int offset)
        {
            System.err.println("Critical error: Can't execute code in low VGA memory.");
            throw new IllegalStateException("Can't exec code in low VGA memory");
        }

        public int executeProtected(Processor cpu, int offset)
        {
            System.err.println("Critical error: Can't execute code in low VGA memory.");
            throw new IllegalStateException("Can't exec code in low VGA memory");
        }

        public int executeVirtual8086(Processor cpu, int offset)
        {
            System.err.println("Critical error: Can't execute code in low VGA memory.");
            throw new IllegalStateException("Can't exec code in low VGA memory");
        }

        public void loadInitialContents(int address, byte[] buf, int off, int len) {
            System.err.println("Critical error: LoadInitialContents() not supported for low VGA memory.");
            throw new UnsupportedOperationException("LoadIntiialContents not implemented for low VGA memory");
        }
    }

    public static class VGARAMIORegion extends MemoryMappedIORegion
    {
        private byte[] buffer;
        private int startAddress;
        private boolean[] dirtyPages;

        public void dumpSRPartial(SRDumper output) throws IOException
        {
            super.dumpSRPartial(output);
            output.dumpArray(buffer);
            output.dumpInt(startAddress);
            output.dumpArray(dirtyPages);
        }

        public VGARAMIORegion(SRLoader input) throws IOException
        {
            super(input);
            buffer = input.loadArrayByte();
            startAddress = input.loadInt();
            dirtyPages = input.loadArrayBoolean();
        }

        public void dumpStatusPartial(StatusDumper output)
        {
            super.dumpStatusPartial(output);
            output.println("\tstartAddress " + startAddress);
            output.printArray(buffer, "buffer");
            output.printArray(dirtyPages, "dirtyPages");
        }

        public void dumpStatus(StatusDumper output)
        {
            if(output.dumped(this))
                return;

            output.println("#" + output.objectNumber(this) + ": VGARAMIORegion:");
            dumpStatusPartial(output);
            output.endObject();
        }

        public VGARAMIORegion()
        {
            //             buffer = new byte[VGA_RAM_SIZE];
            buffer = new byte[INIT_VGA_RAM_SIZE];
            dirtyPages = new boolean[(VGA_RAM_SIZE >>> PAGE_SHIFT) + 1];
            for(int i = 0; i < dirtyPages.length; i++)
                dirtyPages[i] = false;

            startAddress = -1;
        }

        private void increaseVGARAMSize(int offset)
        {
            if((offset < 0) || (offset >= VGA_RAM_SIZE))
                throw new ArrayIndexOutOfBoundsException("tried to access outside of memory bounds");

            int newSize = buffer.length;
            while (newSize <= offset)
                newSize = newSize << 1;

            if(newSize > VGA_RAM_SIZE)
                newSize = VGA_RAM_SIZE;

            byte[] newBuf = new byte[newSize];
            System.arraycopy(buffer, 0, newBuf, 0, buffer.length);
            buffer = newBuf;
        }

        public void copyContentsIntoArray(int address, byte[] buf, int off, int len)
        {
            System.arraycopy(buffer, address, buf, off, len);
        }

        public void copyArrayIntoContents(int address, byte[] buf, int off, int len)
        {
            System.arraycopy(buf, off, buffer, address, len);
        }

        public void clear()
        {
            for(int i = 0; i < buffer.length; i++)
                buffer[i] = 0;

            for(int i = 0; i < dirtyPages.length; i++)
                dirtyPages[i] = false;
        }

        public void clear(int start, int length)
        {
            int limit = start + length;
            if(limit > getSize()) throw new ArrayIndexOutOfBoundsException("Attempt to clear outside of memory bounds");
            try {
                for(int i = start; i < limit; i++)
                    buffer[i] = 0;
            } catch (ArrayIndexOutOfBoundsException e) {}

            int pageStart = start >>> PAGE_SHIFT;
            int pageLimit = (limit - 1) >>> PAGE_SHIFT;
            for(int i = pageStart; i <= pageLimit; i++)
                dirtyPages[i] = true;
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

        public int executeReal(Processor cpu, int offset)
        {
            System.err.println("Critical error: Can't execute code in VGA memory.");
            throw new IllegalStateException("Can't exec code in VGA memory");
        }

        public int executeProtected(Processor cpu, int offset)
        {
            System.err.println("Critical error: Can't execute code in VGA memory.");
            throw new IllegalStateException("Can't exec code in VGA memory");
        }

        public int executeVirtual8086(Processor cpu, int offset)
        {
            System.err.println("Critical error: Can't execute code in VGA memory.");
            throw new IllegalStateException("Can't exec code in VGA memory");
        }

        public boolean isAllocated()
        {
            return true;
        }

        public void loadInitialContents(int address, byte[] buf, int off, int len) {
            System.err.println("Critical error: LoadInitialContents() not supported for VGA memory.");
            throw new UnsupportedOperationException("LoadIntiialContents not supported for VGA mnemory.");
        }

    }

    //Public Methods Used By Output Device
    public final void updateDisplay()
    {

        updatingScreen = true;

        outputDevice.resetDirtyRegion();

        boolean fullUpdate = updated;
        int detGraphicMode;
        if((attributeRegisterIndex & 0x20) == 0)
            detGraphicMode = GMODE_BLANK;
        else
            detGraphicMode = graphicsRegister[GR_INDEX_MISC] & 1;

        if(detGraphicMode != this.graphicMode)
        {
            this.graphicMode = detGraphicMode;
            fullUpdate = true;
        }

        switch(graphicMode)
        {
        case GMODE_TEXT:
            drawText(fullUpdate);
            break;
        case GMODE_GRAPH:
            drawGraphic(fullUpdate);
            break;
        case GMODE_BLANK:
        default:
            drawBlank(fullUpdate);
            break;
        }

        updatingScreen = false;
    }


    private final void drawText(boolean fullUpdate)
    {
        boolean temp = updatePalette16();
        fullUpdate |= temp;
        int[] palette = lastPalette;

        /* compute font data address (in plane 2) */
        int v = this.sequencerRegister[SR_INDEX_CHAR_MAP_SELECT];

        int offset = (((v >>> 4) & 1) | ((v << 1) & 6)) * 8192 * 4 + 2;
        if(offset != this.fontOffset[0]) {
            this.fontOffset[0] = offset;
            fullUpdate = true;
        }


        offset = (((v >>> 5) & 1) | ((v >>> 1) & 6)) * 8192 * 4 + 2;
        if(offset != this.fontOffset[1]) {
            this.fontOffset[1] = offset;
            fullUpdate = true;
        }

        if((this.planeUpdated & (1 << 2)) != 0) {
            /* if the plane 2 was modified since the last display, it
               indicates the font may have been modified */
            this.planeUpdated = 0;
            fullUpdate = true;
        }

        int srcIndex = this.startAddress * 4;

        /* total width and height */
        int charHeight = (crtRegister[CR_INDEX_MAX_SCANLINE] & 0x1f) + 1;
        int charWidth = 8;
        if((sequencerRegister[SR_INDEX_CLOCKING_MODE] & 0x01) == 0)
            charWidth = 9;
        if((sequencerRegister[SR_INDEX_CLOCKING_MODE] & 0x08) != 0)
            charWidth = 16; /* NOTE: no 18 pixel wide */

        int width = crtRegister[CR_INDEX_HORZ_DISPLAY_END] + 1;
        int height;
        if(crtRegister[CR_INDEX_VERT_TOTAL] == 100) {
            /* ugly hack for CGA 160x100x16 */
            height = 100;
        } else {
            height = crtRegister[CR_INDEX_VERT_DISPLAY_END] | ((crtRegister[CR_INDEX_OVERFLOW] & 0x02) << 7) | ((crtRegister[CR_INDEX_OVERFLOW] & 0x40) << 3);
            height = (height + 1) / charHeight;
        }

        if((height * width) > CH_ATTR_SIZE) {
            /* better than nothing: exit if transient size is too big */
            return;
        }

        if((width != this.lastWidth) || (height != this.lastHeight) || (charWidth != this.lastCW) ||
            (charHeight != this.lastCH)) {
            this.lastScreenWidth = width * charWidth;
            this.lastScreenHeight = height * charHeight;
            outputDevice.resizeDisplay(this.lastScreenWidth, this.lastScreenHeight);
            this.lastWidth = width;
            this.lastHeight = height;
            this.lastCH = charHeight;
            this.lastCW = charWidth;
            fullUpdate = true;
        }

        int curCursorOffset = ((crtRegister[CR_INDEX_CURSOR_LOC_HIGH] << 8) | crtRegister[CR_INDEX_CURSOR_LOC_LOW]) - this.startAddress;

        if((curCursorOffset != this.cursorOffset) || (crtRegister[CR_INDEX_CURSOR_START] != this.cursorStart) ||
            (crtRegister[CR_INDEX_CURSOR_END] != this.cursorEnd)) {
            /* if the cursor position changed, we updated the old and new
               chars */
            if((this.cursorOffset < CH_ATTR_SIZE) && (this.cursorOffset >= 0))
                this.lastChar[this.cursorOffset] = -1;
            if((curCursorOffset < CH_ATTR_SIZE) && (curCursorOffset >= 0))
                this.lastChar[curCursorOffset] = -1;

            this.cursorOffset = curCursorOffset;
            this.cursorStart = crtRegister[CR_INDEX_CURSOR_START];
            this.cursorEnd = crtRegister[CR_INDEX_CURSOR_END];
        }

        int cursorIndex = (this.startAddress + this.cursorOffset) * 4;
        int lastCharOffset = 0;

        switch (charWidth) {
        case 8:
            for(int charY = 0; charY < height; charY++) {
                int srcOffset = srcIndex;
                for(int charX = 0; charX < width; charX++) {
                    int charShort = 0xffff & ioRegion.getWord(srcOffset);
                    if(fullUpdate || (charShort != this.lastChar[lastCharOffset])) {
                        this.lastChar[lastCharOffset] = charShort;

                        int character = 0xff & charShort;
                        int characterAttribute = charShort >>> 8;

                        int glyphOffset = fontOffset[(characterAttribute >>> 3) & 1] + 32 * 4 * character;
                        int backgroundColor = palette[characterAttribute >>> 4];
                        int foregroundColor = palette[characterAttribute & 0xf];

                        drawGlyph8(outputDevice.getDisplayBuffer(), charY * charHeight * lastScreenWidth + charX * 8,
                            lastScreenWidth, glyphOffset, charHeight, foregroundColor, backgroundColor);
                        outputDevice.dirtyDisplayRegion(charX * 8, charY * charHeight, 8, charHeight);

                        if((srcOffset == cursorIndex) && ((crtRegister[CR_INDEX_CURSOR_START] & 0x20) == 0)) {
                            int lineStart = crtRegister[CR_INDEX_CURSOR_START] & 0x1f;
                            int lineLast = crtRegister[CR_INDEX_CURSOR_END] & 0x1f;
                            /* XXX: check that */
                            if(lineLast > charHeight - 1)
                                lineLast = charHeight - 1;

                            if((lineLast >= lineStart) && (lineStart < charHeight)) {
                                int tempHeight = lineLast - lineStart + 1;
                                drawCursorGlyph8(outputDevice.getDisplayBuffer(),
                                    (charY * charHeight + lineStart) * lastScreenWidth + charX * 8,
                                    lastScreenWidth, tempHeight, foregroundColor, backgroundColor);
                                outputDevice.dirtyDisplayRegion(charX * 8, charY * charHeight + lineStart, 8, tempHeight);
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
            for(int charY = 0; charY < height; charY++) {
                int srcOffset = srcIndex;
                for(int charX = 0; charX < width; charX++) {
                    int charShort = 0xffff & ioRegion.getWord(srcOffset);
                    if(fullUpdate || (charShort != this.lastChar[lastCharOffset])) {
                        this.lastChar[lastCharOffset] = charShort;

                        int character = 0xff & charShort;
                        int characterAttribute = charShort >>> 8;

                        int glyphOffset = fontOffset[(characterAttribute >>> 3) & 1] + 32 * 4 * character;
                        int backgroundColor = palette[characterAttribute >>> 4];
                        int foregroundColor = palette[characterAttribute & 0xf];

                        boolean dup9 = ((character >= 0xb0) && (character <= 0xdf) && ((attributeRegister[AR_INDEX_ATTR_MODE_CONTROL] & 0x04) != 0));
                        drawGlyph9(outputDevice.getDisplayBuffer(), charY * charHeight * lastScreenWidth + charX * 9,
                            lastScreenWidth, glyphOffset, charHeight, foregroundColor, backgroundColor, dup9);
                        outputDevice.dirtyDisplayRegion(charX * 9, charY * charHeight, 9, charHeight);

                        if((srcOffset == cursorIndex) &&((crtRegister[CR_INDEX_CURSOR_START] & 0x20) == 0)) {
                            int lineStart = crtRegister[CR_INDEX_CURSOR_START] & 0x1f;
                            int lineLast = crtRegister[CR_INDEX_CURSOR_END] & 0x1f;
                            /* XXX: check that */
                            if(lineLast > charHeight - 1)
                                lineLast = charHeight - 1;

                            if((lineLast >= lineStart) && (lineStart < charHeight)) {
                                int tempHeight = lineLast - lineStart + 1;
                                drawCursorGlyph9(outputDevice.getDisplayBuffer(),
                                    (charY * charHeight + lineStart) * lastScreenWidth + charX * 9,
                                    lastScreenWidth, tempHeight, foregroundColor, backgroundColor);
                                outputDevice.dirtyDisplayRegion(charX * 9, charY * charHeight + lineStart, 9, tempHeight);
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
            for(int charY = 0; charY < height; charY++) {
                int srcOffset = srcIndex;
                for(int charX = 0; charX < width; charX++) {
                    int charShort = 0xffff & ioRegion.getWord(srcOffset);
                    if(fullUpdate || (charShort != this.lastChar[lastCharOffset])) {
                        this.lastChar[lastCharOffset] = charShort;

                        int character = 0xff & charShort;
                        int characterAttribute = charShort >>> 8;

                        int glyphOffset = fontOffset[(characterAttribute >>> 3) & 1] + 32 * 4 * character;
                        int backgroundColor = palette[characterAttribute >>> 4];
                        int foregroundColor = palette[characterAttribute & 0xf];

                        drawGlyph16(outputDevice.getDisplayBuffer(), charY * charHeight * lastScreenWidth + charX * 16,
                            lastScreenWidth, glyphOffset, charHeight, foregroundColor, backgroundColor);
                        outputDevice.dirtyDisplayRegion(charX * 16, charY * charHeight, 16, charHeight);

                        if((srcOffset == cursorIndex) &&((crtRegister[CR_INDEX_CURSOR_START] & 0x20) == 0)) {
                            int lineStart = crtRegister[CR_INDEX_CURSOR_START] & 0x1f;
                            int lineLast = crtRegister[CR_INDEX_CURSOR_END] & 0x1f;
                            /* XXX: check that */
                            if(lineLast > charHeight - 1)
                                lineLast = charHeight - 1;

                            if((lineLast >= lineStart) && (lineStart < charHeight)) {
                                int tempHeight = lineLast - lineStart + 1;
                                drawCursorGlyph16(outputDevice.getDisplayBuffer(),
                                    (charY * charHeight + lineStart) * lastScreenWidth + charX * 16,
                                    lastScreenWidth, tempHeight, foregroundColor, backgroundColor);
                                outputDevice.dirtyDisplayRegion(charX * 16, charY * charHeight + lineStart, 16, tempHeight);
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
            System.err.println("Warning: Unknown character width " + Integer.valueOf(charWidth) + ".");
            return;
        }
    }

    public abstract static class GraphicsUpdater implements SRDumpable
    {
        protected VGACard upperBackref;

        public void dumpSRPartial(SRDumper output) throws IOException
        {
            output.dumpObject(upperBackref);
        }

        public GraphicsUpdater(SRLoader input) throws IOException
        {
            input.objectCreated(this);
            upperBackref = (VGACard)input.loadObject();
        }

        public GraphicsUpdater(VGACard backref)
        {
            upperBackref = backref;
        }

        public void dumpStatusPartial(StatusDumper output)
        {
            //super.dumpStatusPartial(output); <no superclass 20090704>
            output.println("\tupperBackref <object #" + output.objectNumber(upperBackref) + ">"); if(upperBackref != null) upperBackref.dumpStatus(output);
        }

        public void dumpStatus(StatusDumper output)
        {
            if(output.dumped(this))
                return;

            output.println("#" + output.objectNumber(this) + ": GraphicsUpdater:");
            dumpStatusPartial(output);
            output.endObject();
        }

        abstract int byteWidth(int width);

        abstract void drawLine(int offset, int width, int y, int dispWidth);

        void updateDisplay(int width, int height, int dispWidth, boolean fullUpdate, int multiScan)
        {
            int multiRun = multiScan;
            int addr1 = 4 * (upperBackref.startAddress + upperBackref.byteSkip);
            if((upperBackref.shiftControl != 0 && upperBackref.shiftControl != 1))
            {
                //addr1 += upperBackref.pixelPanning;
                //addr1 += (upperBackref.pixelPanning + 2) >> 1;
            } else {
                //addr1 += (upperBackref.pixelPanning) >> 1;
            }
            //int lineSize = width; // get the line size from the display device??

            int y1 = 0;
            boolean addrMunge1 = (upperBackref.crtRegister[CR_INDEX_CRTC_MODE_CONTROL] & 1) == 0;
            boolean addrMunge2 = (upperBackref.crtRegister[CR_INDEX_CRTC_MODE_CONTROL] & 2) == 0;
            boolean addrMunge = addrMunge1 || addrMunge2;
            int mask = (upperBackref.crtRegister[CR_INDEX_CRTC_MODE_CONTROL] & 3) ^ 3;

            int pageMin = Integer.MAX_VALUE;
            int pageMax = Integer.MIN_VALUE;

            for(int y = 0; y < height; y++)
            {
                int addr = addr1;

                if(addrMunge)
                {
                    if(addrMunge1)
                    {
                        /* CGA compatibility handling */
                        int shift = 14 + ((upperBackref.crtRegister[CR_INDEX_CRTC_MODE_CONTROL] >>> 6) & 1);
                        addr = (addr & ~(1 << shift)) | ((y1 & 1) << shift);
                    }

                    if(addrMunge2)
                        addr = (addr & ~0x8000) | ((y1 & 2) << 14);
                }

                int pageStart = addr >>> PAGE_SHIFT;
                int pageEnd = (addr + byteWidth(width) - 1) >>> PAGE_SHIFT;
                for(int i = pageStart; i <= pageEnd; i++) {
                    if(fullUpdate || upperBackref.ioRegion.pageIsDirty(i)) {
                        pageMin = Math.min(pageMin, pageStart);
                        pageMax = Math.max(pageMax, pageEnd);
                        drawLine(addr, width, y, dispWidth);
                        break;
                    }
                }

                if(multiRun == 0) {
                    if((y1 & mask) == mask)
                        addr1 += upperBackref.lineOffset;
                    y1++;
                    multiRun = multiScan;
                } else
                    multiRun--;

                /* line compare acts on the displayed lines */
                if(y == upperBackref.lineCompare) {
                    addr1 = 0;
                    if((upperBackref.attributeRegister[AR_INDEX_ATTR_MODE_CONTROL] & 0x20) != 0)
                        upperBackref.usePixelPanning = 0; //Disable panning for rest of screen.
                }
            }

            for(int i = pageMin; i <= pageMax; i++)
                upperBackref.ioRegion.cleanPage(i);
        }
    }

    public static class DrawLine2 extends GraphicsUpdater
    {
        public void dumpSRPartial(SRDumper output) throws IOException
        {
            super.dumpSRPartial(output);
        }

        public DrawLine2(SRLoader input) throws IOException
        {
            super(input);
        }

        public DrawLine2(VGACard backref)
        {
            super(backref);
        }

        public void dumpStatusPartial(StatusDumper output)
        {
            super.dumpStatusPartial(output);
        }

        public void dumpStatus(StatusDumper output)
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

        void drawLine(int offset, int width, int y, int dispWidth)
        {
            int[] dest = upperBackref.outputDevice.getDisplayBuffer();
            int minindex = y * dispWidth;
            int index = y * dispWidth - ((upperBackref.usePixelPanning) & 0x0F);

            int[] palette = upperBackref.lastPalette;
            int planeMask = mask16[upperBackref.attributeRegister[AR_INDEX_COLOR_PLANE_ENABLE] & 0xf];
            width += (minindex - index);
            width >>>= 3;

            do {
                int data = upperBackref.ioRegion.getDoubleWord(offset);
                data &= planeMask;

                int v = expand2[data & 0xff];
                v |= expand2[(data >>> 16) & 0xff] << 2;
                for(int x = 12; x >= 0; x -= 4)
                    if(index < minindex)
                        index++;
                    else
                        dest[index++] = palette[(v >>> x) & 0xf];

                v = expand2[(data >>> 8) & 0xff];
                v |= expand2[(data >>> 24) & 0xff] << 2;

                for(int x = 12; x >= 0; x -= 4)
                    if(index < minindex)
                        index++;
                    else
                        dest[index++] = palette[(v >>> x) & 0xf];

                offset += 4;
            } while (--width > 0);

            upperBackref.outputDevice.dirtyDisplayRegion(0, y, dispWidth, 1);
        }
    }

    public static class DrawLine2d2 extends GraphicsUpdater
    {
        public void dumpSRPartial(SRDumper output) throws IOException
        {
            super.dumpSRPartial(output);
        }

        public DrawLine2d2(SRLoader input) throws IOException
        {
            super(input);
        }

        public DrawLine2d2(VGACard backref)
        {
            super(backref);
        }

        public void dumpStatusPartial(StatusDumper output)
        {
            super.dumpStatusPartial(output);
        }

        public void dumpStatus(StatusDumper output)
        {
            if(output.dumped(this))
                return;

            output.println("#" + output.objectNumber(this) + ": DrawLine2d2:");
            dumpStatusPartial(output);
            output.endObject();
        }

        int byteWidth(int width)
        {
            return (width/2);
        }

        void drawLine(int offset, int width, int y, int dispWidth)
        {
            int[] dest = upperBackref.outputDevice.getDisplayBuffer();
            int minindex = y * dispWidth;
            int index = y * dispWidth - (((upperBackref.usePixelPanning) & 0x0F));

            int[] palette = upperBackref.lastPalette;
            int planeMask = mask16[upperBackref.attributeRegister[AR_INDEX_COLOR_PLANE_ENABLE] & 0xf];
            width += (minindex - index);
            width >>>= 3;

            do {
                int data = upperBackref.ioRegion.getDoubleWord(offset);
                data &= planeMask;

                int v = expand2[data & 0xff];
                v |= expand2[(data >>> 16) & 0xff] << 2;

                for(int x = 12; x >= 0; x -= 4)
                    if(index < minindex)
                        index += 2;
                    else
                        dest[index++] = dest[index++] = palette[(v >>> x) & 0xf];

                v = expand2[(data >>> 8) & 0xff];
                v |= expand2[(data >>> 24) & 0xff] << 2;

                for(int x = 12; x >= 0; x -= 4)
                    if(index < minindex)
                        index += 2;
                    else
                        dest[index++] = dest[index++] = palette[(v >>> x) & 0xf];

                offset += 4;
            } while (--width > 0);

            upperBackref.outputDevice.dirtyDisplayRegion(0, y, dispWidth, 1);
        }
    }

    public static class DrawLine4 extends GraphicsUpdater
    {
        public void dumpSRPartial(SRDumper output) throws IOException
        {
            super.dumpSRPartial(output);
        }

        public DrawLine4(SRLoader input) throws IOException
        {
            super(input);
        }

        public DrawLine4(VGACard backref)
        {
            super(backref);
        }

        public void dumpStatusPartial(StatusDumper output)
        {
            super.dumpStatusPartial(output);
        }

        public void dumpStatus(StatusDumper output)
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

        void drawLine(int offset, int width, int y, int dispWidth)
        {
            int[] dest = upperBackref.outputDevice.getDisplayBuffer();
            int minindex = y * dispWidth;
            int index = y * dispWidth - ((upperBackref.usePixelPanning) & 0x0F);

            int[] palette = upperBackref.lastPalette;
            int planeMask = mask16[upperBackref.attributeRegister[AR_INDEX_COLOR_PLANE_ENABLE] & 0xf];
            width += (minindex - index);
            width >>>= 3;

            do {
                int data = upperBackref.ioRegion.getDoubleWord(offset) & planeMask;

                int v = expand4[data & 0xff];
                data >>>= 8;
                v |= expand4[data & 0xff] << 1;
                data >>>= 8;
                v |= expand4[data & 0xff] << 2;
                data >>>= 8;
                v |= expand4[data & 0xff] << 3;

                for(int x = 28; x >= 0; x -= 4)
                    if(index < minindex)
                        index++;
                    else
                        dest[index++] = palette[(v >>> x) & 0xF];

                offset += 4;
            } while (--width != 0);

            upperBackref.outputDevice.dirtyDisplayRegion(0, y, dispWidth , 1);
        }
    }

    public static class DrawLine4d2 extends GraphicsUpdater
    {
        public void dumpSRPartial(SRDumper output) throws IOException
        {
            super.dumpSRPartial(output);
        }

        public DrawLine4d2(SRLoader input) throws IOException
        {
            super(input);
        }

        public DrawLine4d2(VGACard backref)
        {
            super(backref);
        }

        public void dumpStatusPartial(StatusDumper output)
        {
            super.dumpStatusPartial(output);
        }

        public void dumpStatus(StatusDumper output)
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

        void drawLine(int offset, int width, int y, int dispWidth)
        {
            int[] dest = upperBackref.outputDevice.getDisplayBuffer();
            int minindex = y * dispWidth;
            int maxindex = (y + 1) * dispWidth;
            int index = y * dispWidth - (((upperBackref.usePixelPanning) & 0x0F) << 1);

            int[] palette = upperBackref.lastPalette;
            int planeMask = mask16[upperBackref.attributeRegister[AR_INDEX_COLOR_PLANE_ENABLE] & 0xf];
            width += (minindex - index);
            width >>>= 3;

            do {
                int data = upperBackref.ioRegion.getDoubleWord(offset);
                data &= planeMask;

                int v = expand4[data & 0xff];
                v |= expand4[(data >>> 8) & 0xff] << 1;
                v |= expand4[(data >>> 16) & 0xff] << 2;
                v |= expand4[(data >>> 24) & 0xff] << 3;

                for(int x = 28; x >= 0; x -= 4)
                    if(index >= maxindex)
                        break;
                    else if(index < minindex)
                        index += 2;
                    else
                         dest[index++] = dest[index++] = palette[(v >>> x) & 0xF];

                offset += 4;
            } while (--width > 0);

            upperBackref.outputDevice.dirtyDisplayRegion(0, y, dispWidth, 1);
        }
    }

    public static class DrawLine8d2 extends GraphicsUpdater
    {
        public void dumpSRPartial(SRDumper output) throws IOException
        {
            super.dumpSRPartial(output);
        }

        public DrawLine8d2(SRLoader input) throws IOException
        {
            super(input);
        }

        public DrawLine8d2(VGACard backref)
        {
            super(backref);
        }

        public void dumpStatusPartial(StatusDumper output)
        {
            super.dumpStatusPartial(output);
        }

        public void dumpStatus(StatusDumper output)
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

        void drawLine(int offset, int width, int y, int dispWidth)
        {
            int[] dest = upperBackref.outputDevice.getDisplayBuffer();
            int minindex = y * dispWidth;
            int index = y * dispWidth - ((upperBackref.usePixelPanning + (upperBackref.vgaScroll2HackFlag ? 2 : 0)) & 0x0F);

            int[] palette = upperBackref.lastPalette;
            width += (minindex - index);
            width >>>= 1;

            do
            {
                int val = palette[0xFF & upperBackref.ioRegion.getByte(offset++)];
                if(index < minindex)
                    index++;
                else
                    dest[index++] = val;
                if(index < minindex)
                    index++;
                else
                    dest[index++] = val;
                width--;
            }
            while (width > 0);

            upperBackref.outputDevice.dirtyDisplayRegion(0, y, dispWidth, 1);
        }
    }

    public static class DrawLine8 extends GraphicsUpdater
    {
        public void dumpSRPartial(SRDumper output) throws IOException
        {
            super.dumpSRPartial(output);
        }

        public DrawLine8(SRLoader input) throws IOException
        {
            super(input);
        }

        public DrawLine8(VGACard backref)
        {
            super(backref);
        }

        public void dumpStatusPartial(StatusDumper output)
        {
            super.dumpStatusPartial(output);
        }

        public void dumpStatus(StatusDumper output)
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

        void drawLine(int offset, int width, int y, int dispWidth)
        {
            int[] dest = upperBackref.outputDevice.getDisplayBuffer();
            int minindex = y * dispWidth;
            int index = y * dispWidth - ((upperBackref.usePixelPanning) & 0x0F);
            width += (minindex - index);

            int[] palette = upperBackref.lastPalette;
            do
            {
                if(index >= minindex)
                    dest[index] = palette[0xFF & upperBackref.ioRegion.getByte(offset++)];
                index++;
                width--;
            }
            while (width > 0);

            upperBackref.outputDevice.dirtyDisplayRegion(0, y, dispWidth, 1);
        }
    }

    public static class DrawLine15 extends GraphicsUpdater
    {
        public void dumpSRPartial(SRDumper output) throws IOException
        {
            super.dumpSRPartial(output);
        }

        public DrawLine15(SRLoader input) throws IOException
        {
            super(input);
        }

        public DrawLine15(VGACard backref)
        {
            super(backref);
        }

        public void dumpStatusPartial(StatusDumper output)
        {
            super.dumpStatusPartial(output);
        }

        public void dumpStatus(StatusDumper output)
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

        void drawLine(int offset, int width, int y, int dispWidth)
        {
            int[] dest = upperBackref.outputDevice.getDisplayBuffer();

            int i = y * dispWidth;
            do {
                int v = 0xffff & upperBackref.ioRegion.getWord(offset);
                int r = (v >>> 7) & 0xf8;
                int g = (v >>> 2) & 0xf8;
                int b = (v << 3)  & 0xf8;
                dest[i] = upperBackref.outputDevice.rgbToPixel(r, g, b);
                offset += 2;
                i++;
            } while (--width != 0);

            upperBackref.outputDevice.dirtyDisplayRegion(0, y, dispWidth, 1);
        }
    }

    public static class DrawLine16 extends GraphicsUpdater
    {
        public void dumpSRPartial(SRDumper output) throws IOException
        {
            super.dumpSRPartial(output);
        }

        public DrawLine16(SRLoader input) throws IOException
        {
            super(input);
        }

        public DrawLine16(VGACard backref)
        {
            super(backref);
        }

        public void dumpStatusPartial(StatusDumper output)
        {
            super.dumpStatusPartial(output);
        }

        public void dumpStatus(StatusDumper output)
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

        void drawLine(int offset, int width, int y, int dispWidth)
        {
            int[] dest = upperBackref.outputDevice.getDisplayBuffer();

            int i = y * dispWidth;
            do {
                int v = 0xffff & upperBackref.ioRegion.getWord(offset);
                int r = (v >>> 8) & 0xf8;
                int g = (v >>> 3) & 0xfc;
                int b = (v << 3)  & 0xf8;
                dest[i] = upperBackref.outputDevice.rgbToPixel(r, g, b);
                offset += 2;
                i++;
            } while (--width != 0);

            upperBackref.outputDevice.dirtyDisplayRegion(0, y, dispWidth, 1);
        }
    }

    public static class DrawLine24  extends GraphicsUpdater
    {
        public void dumpSRPartial(SRDumper output) throws IOException
        {
            super.dumpSRPartial(output);
        }

        public DrawLine24(SRLoader input) throws IOException
        {
            super(input);
        }

        public DrawLine24(VGACard backref)
        {
            super(backref);
        }

        public void dumpStatusPartial(StatusDumper output)
        {
            super.dumpStatusPartial(output);
        }

        public void dumpStatus(StatusDumper output)
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

        void drawLine(int offset, int width, int y, int dispWidth)
        {
            int[] dest = upperBackref.outputDevice.getDisplayBuffer();

            int i = y * dispWidth;
            do {
                int b = 0xFF & upperBackref.ioRegion.getByte(offset++);
                int g = 0xFF & upperBackref.ioRegion.getByte(offset++);
                int r = 0xFF & upperBackref.ioRegion.getByte(offset++);

                dest[i++] = upperBackref.outputDevice.rgbToPixel(r, g, b);
            } while (--width != 0);

            upperBackref.outputDevice.dirtyDisplayRegion(0, y, dispWidth, 1);
        }
    }

    public static class DrawLine32 extends GraphicsUpdater
    {
        public void dumpSRPartial(SRDumper output) throws IOException
        {
            super.dumpSRPartial(output);
        }

        public DrawLine32(SRLoader input) throws IOException
        {
            super(input);
        }

        public DrawLine32(VGACard backref)
        {
            super(backref);
        }

        public void dumpStatusPartial(StatusDumper output)
        {
            super.dumpStatusPartial(output);
        }

        public void dumpStatus(StatusDumper output)
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

        void drawLine(int offset, int width, int y, int dispWidth)
        {
            int[] dest = upperBackref.outputDevice.getDisplayBuffer();

            int i = y * dispWidth;
            do {
                int b = 0xff & upperBackref.ioRegion.getByte(offset++);
                int g = 0xff & upperBackref.ioRegion.getByte(offset++);
                int r = 0xff & upperBackref.ioRegion.getByte(offset++);
                offset++;

                dest[i++] = upperBackref.outputDevice.rgbToPixel(r, g, b);
            } while (--width != 0);

            upperBackref.outputDevice.dirtyDisplayRegion(0, y, dispWidth, 1);
        }
    }

    private final void drawGraphic(boolean fullUpdate)
    {
        boolean temp = false;

        int width = (crtRegister[CR_INDEX_HORZ_DISPLAY_END] + 1) * 8;
        int height = (crtRegister[CR_INDEX_VERT_DISPLAY_END] | ((crtRegister[CR_INDEX_OVERFLOW] & 0x02) << 7) |
            ((crtRegister[CR_INDEX_OVERFLOW] & 0x40) << 3)) + 1;

        if((vbeRegs[VBE_DISPI_INDEX_ENABLE] & VBE_DISPI_ENABLED) != 0) {
            //Override size from VBE.
            width = vbeRegs[VBE_DISPI_INDEX_XRES];
            height = vbeRegs[VBE_DISPI_INDEX_YRES];
        }

        int dispWidth = width;
        int shiftControlBuffer = (graphicsRegister[GR_INDEX_GRAPHICS_MODE] >>> 5) & 3;
        int doubleScanBuffer = crtRegister[CR_INDEX_MAX_SCANLINE] >>> 7;

        int multiScan;
        if(shiftControlBuffer != 1)
            multiScan = (((crtRegister[CR_INDEX_MAX_SCANLINE] & 0x1f) + 1) << doubleScanBuffer) - 1;
        else {
            /* in CGA modes, multi_scan is ignored */
            /* XXX: is it correct ? */
            multiScan = doubleScanBuffer;
        }

        if(shiftControlBuffer != shiftControl || doubleScanBuffer != doubleScan)
        {
            fullUpdate = true;
            this.shiftControl = shiftControlBuffer;
            this.doubleScan = doubleScanBuffer;
        }

        GraphicsUpdater graphicUpdater = null;
        if(shiftControl == 0)
        {
            temp = updatePalette16();
            fullUpdate |= temp;
            if((sequencerRegister[SR_INDEX_CLOCKING_MODE] & 8) != 0 )
            {
                graphicUpdater = VGA_DRAW_LINE4D2;
                dispWidth <<= 1;
            }
            else
                graphicUpdater = VGA_DRAW_LINE4;
        }
        else if(shiftControl == 1)
        {
            temp = updatePalette16();
            fullUpdate |= temp;
            if((sequencerRegister[SR_INDEX_CLOCKING_MODE] & 8) != 0)
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
            if((vbeRegs[VBE_DISPI_INDEX_ENABLE] & VBE_DISPI_ENABLED) != 0)
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

        if((dispWidth != lastWidth) || (height != lastHeight))
        {
            fullUpdate = true;
            lastScreenWidth = lastWidth = dispWidth;
            lastScreenHeight = lastHeight = height;
            outputDevice.resizeDisplay(lastScreenWidth, lastScreenHeight);
        }

        graphicUpdater.updateDisplay(width, height, dispWidth, fullUpdate, multiScan);
    }

    private final void drawBlank(boolean fullUpdate)
    {
        if(!fullUpdate)
            return;
        if((lastScreenWidth <= 0) || (lastScreenHeight <= 0))
            return;

        int[] rawBytes = outputDevice.getDisplayBuffer();
        int black = outputDevice.rgbToPixel(0, 0, 0);
        for(int i=rawBytes.length-1; i>=0; i--)
            rawBytes[i] = black;

        outputDevice.dirtyDisplayRegion(0, 0, lastScreenWidth, lastScreenHeight);
    }

    private final boolean updatePalette16()
    {
        boolean fullUpdate = false;
        if(paletteLocked) return false;
        int[] palette = lastPalette;

        for(int colorIndex = AR_INDEX_PALLETE_MIN; colorIndex <= AR_INDEX_PALLETE_MAX; colorIndex++)
        {
            int v = attributeRegister[colorIndex];
            if((attributeRegister[AR_INDEX_ATTR_MODE_CONTROL] & 0x80) != 0)
                v = ((attributeRegister[AR_INDEX_COLOR_SELECT] & 0xf) << 4) | (v & 0xf);
            else
                v = ((attributeRegister[AR_INDEX_COLOR_SELECT] & 0xc) << 4) | (v & 0x3f);

            v *= 3;
            int col;
            if((vbeRegs[VBE_DISPI_INDEX_ENABLE] & VBE_DISPI_8BITDAC) == VBE_DISPI_8BITDAC)
                col = outputDevice.rgbToPixel(this.palette[v], this.palette[v+1], this.palette[v+2]);
            else
                col = outputDevice.rgbToPixel(c6to8(this.palette[v]), c6to8(this.palette[v+1]),
                    c6to8(this.palette[v+2]));
            if(col != palette[colorIndex])
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
        if(paletteLocked) return false;
        int[] palette = lastPalette;

        for(int i = 0, v = 0; i < 256; i++, v+=3) {
            int col;
            if((vbeRegs[VBE_DISPI_INDEX_ENABLE] & VBE_DISPI_8BITDAC) == VBE_DISPI_8BITDAC)
                col = outputDevice.rgbToPixel(this.palette[v], this.palette[v+1], this.palette[v+2]);
            else
                col = outputDevice.rgbToPixel(c6to8(this.palette[v]), c6to8(this.palette[v+1]),
                    c6to8(this.palette[v+2]));
            if(col != palette[i]) {
                fullUpdate = true;
                palette[i] = col;
            }
        }
        return fullUpdate;
    }

    private final boolean updateBasicParameters()
    {
        int curStartAddress, curLineOffset, curByteSkip, curPixelPanning;
        if((vbeRegs[VBE_DISPI_INDEX_ENABLE] & VBE_DISPI_ENABLED) != 0) {
            curLineOffset = this.vbeLineOffset;
            curStartAddress = this.vbeStartAddress;
            curByteSkip = 0;
            curPixelPanning = 0;
        } else {
            /* compute curLineOffset in bytes */
            curLineOffset = crtRegister[CR_INDEX_OFFSET];
            curLineOffset <<= 3;

            /* starting address */
            curStartAddress = crtRegister[CR_INDEX_START_ADDR_LOW] | (crtRegister[CR_INDEX_START_ADDR_HIGH] << 8);

            curPixelPanning = ((attributeRegister[AR_INDEX_HORIZ_PIXEL_PANNING]
                                )& 0xF);
            curByteSkip = (crtRegister[CR_INDEX_PRESET_ROW_SCAN] >> 5) & 3;
        }

        /* line compare */
        int curLineCompare = crtRegister[CR_INDEX_LINE_COMPARE] | ((crtRegister[CR_INDEX_OVERFLOW] & 0x10) << 4) | ((crtRegister[CR_INDEX_MAX_SCANLINE] & 0x40) << 3);

        redoTimingCalculations();

        if((curStartAddress != this.startAddress) || (curPixelPanning != pixelPanning) || (curByteSkip != byteSkip)) {
            vgaScrollChanges++;
            updateStatus();
        }

        if((curLineOffset != this.lineOffset) || (curStartAddress != this.startAddress) ||
            (curLineCompare != this.lineCompare) || (curPixelPanning != pixelPanning) || (curByteSkip != byteSkip))
        {
            this.lineOffset = curLineOffset;
            this.startAddress = curStartAddress;
            this.lineCompare = curLineCompare;
            this.usePixelPanning = this.pixelPanning = curPixelPanning;
            this.byteSkip = curByteSkip;
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
            for(int i = 7; i >= 0; i--) {
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
            for(int i = 7; i >= 0; i--) {
                int pixel = ((-((fontData >>> i) & 1)) & xorColor) ^ backgroundColor;
                buffer[startOffset++] = pixel;
            }
            fontData = expand4to8[rawData & 0x0f];
            for(int i = 7; i >= 0; i--) {
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

        if(dup9) {
            do {
                int fontData = ioRegion.getByte(glyphOffset);

                for(int i=7; i>=0; i--) {
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

                for(int i=7; i>=0; i--) {
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
            for(int i = 7; i >= 0; i--)
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
            for(int i = 7; i >= 0; i--)
            {
                int pixel = ((-((fontData >>> i) & 1)) & xorColor) ^ backgroundColor;
                buffer[startOffset++] = pixel;
            }
            fontData = expand4to8[rawData & 0x0f];
            for(int i = 7; i >= 0; i--)
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
            for(int i=7; i>=0; i--) {
                int pixel = ((-((fontData >>> i) & 1)) & xorColor) ^ backgroundColor;
                buffer[startOffset++] = pixel;
            }
            buffer[startOffset++] = buffer[startOffset-2];
            glyphOffset++;
            startOffset += scanSize;
        } while (--charHeight != 0);
    }

    private void redoTimingCalculations()
    {
        if(SYSFLAG_VGATIMINGMETHOD == 0) return;

        /* TODO: Add VBE support (these values assume plain VGA) */
        if((vbeRegs[VBE_DISPI_INDEX_ENABLE] & VBE_DISPI_ENABLED) != 0)
            //Nasty hack: BGA doesn't have way of specifying clocking, so just reuse previous values.
            return;

        // 3D5: crtRegister[]
        // 3C0: attributeRegister[]
        // 3C5: sequencerRegister[]
        int htotal = crtRegister[0x00];
        int hdend  = crtRegister[CR_INDEX_HORZ_DISPLAY_END];
        int hbend  = crtRegister[0x03] & 0x1F;
        int hbstart = crtRegister[0x02];
        int hrstart = crtRegister[0x04];

        int vtotal = crtRegister[CR_INDEX_VERT_TOTAL] | ((crtRegister[CR_INDEX_OVERFLOW] & 1) << 8);
        int vdend  = crtRegister[CR_INDEX_VERT_DISPLAY_END] | ((crtRegister[CR_INDEX_OVERFLOW] & 2) << 7);
        int vbstart = crtRegister[0x15] | ((crtRegister[CR_INDEX_OVERFLOW] & 8) << 5);
        int vrstart = crtRegister[0x10] + ((crtRegister[CR_INDEX_OVERFLOW] & 4) << 6);
        htotal += 3;
        hbend |= ((crtRegister[0x05] & 0x80) >>> 2);
        vtotal |= ((crtRegister[CR_INDEX_OVERFLOW] & 0x20) << 4);
        vdend |= ((crtRegister[CR_INDEX_OVERFLOW] & 0x40) << 3);
        vbstart |= ((crtRegister[CR_INDEX_MAX_SCANLINE] & 0x20) << 4);
        vrstart |= ((crtRegister[CR_INDEX_OVERFLOW] & 0x80) << 2);
        int vbend = crtRegister[0x16] & 0x3F;
        htotal += 2;
        vtotal += 2;
        hdend += 1;
        vdend += 1;
        hbend = hbstart + ((hbend - hbstart) & 0x3F);
        int hrend = crtRegister[0x16] & 0x1F;
        hrend = (hrend - hrstart) & 0x1F;
        if(hrend == 0) hrend = hrstart + 0x1f + 1;
        else hrend = hrstart + hrend;
        int vrend = crtRegister[CR_INDEX_VERT_RETRACE_END] & 0x0F;
        vrend = (vrend - vrstart) & 0xF;

        if(vrend == 0) vrend = vrstart + 0xf + 1;
        else vrend = vrstart + vrend;

        vbend = (vbend - vbstart) & 0x3f;
        if(vbend == 0) vbend = vbstart + 0x3f + 1;
        else vbend = vbstart + vbend;

        int clock = 8;
        if(((miscellaneousOutputRegister >>> 2) & 3) == 0) {
            clock = 9;
        }

        if(htotal <= 10) htotal = 256; // Reasonable pre-BIOS default
        if(vtotal <= 10) vtotal = 256; // Reasonable pre-BIOS default

        if((sequencerRegister[SR_INDEX_CLOCKING_MODE] & 1) != 0) clock *= 8; else clock *= 9;
        if((sequencerRegister[SR_INDEX_CLOCKING_MODE] & 8) != 0) htotal *= 2;

        int address_line_total = (crtRegister[CR_INDEX_MAX_SCANLINE] & 0x1F) + 1;
        if((crtRegister[CR_INDEX_MAX_SCANLINE] & 0x80) != 0) address_line_total *= 2;
        ////////////
        // All times below expressed in VGA cycles.
        // Horizontal total (that's how long a line takes with whistles and bells)
        draw_htotal = htotal * clock;
        // The time a complete video frame takes
        draw_vtotal = draw_htotal * vtotal;
        // Start and End of horizontal blanking
        draw_hblkstart = hbstart * clock;
        draw_hblkend   = hbend * clock;
        // Start and End of vertical blanking
        //double draw_vblkstart = vbstart * draw_htotal;
        //double draw_vblkend = vbend * draw_htotal;
        // Start and End of vertical retrace pulse
        draw_vrstart = vrstart * draw_htotal;
        draw_vrend = vrend * draw_htotal;
        // Display end
        draw_vdend = vdend * draw_htotal;
        /*
        System.err.println("htotal "+htotal+", vtotal "+vtotal);
        System.err.println("draw_htotal " + draw_htotal + " draw_vtotal " + draw_vtotal);
        System.err.println("draw_hblkstart " + draw_hblkstart + " draw_hblkend " + draw_hblkend);
        System.err.println("draw_vrstart " + draw_vrstart + " draw_vrend " + draw_vrend + " draw_vdend " + draw_vdend);
        */
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

        assignDeviceFunctionNumber(-1);
        setIRQIndex(16);

        putConfigWord(PCI_CONFIG_VENDOR_ID, (short)0x1234); // Dummy
        putConfigWord(PCI_CONFIG_DEVICE_ID, (short)0x1111);
        putConfigWord(PCI_CONFIG_CLASS_DEVICE, (short)0x0300); // VGA Controller
        putConfigByte(PCI_CONFIG_HEADER, (byte)0x00); // header_type

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
        redoTimingCalculations();
    }

    public int getTimerType()
    {
        return 7;
    }

    public void callback()
    {
        switch(SYSFLAG_VGATIMINGMETHOD) {
            case 0: {
                /* Two-phase cycle:
                 *   A) 0..TRACE_TIME:          retracing=false  (90% of frame)
                 *   B) TRACE_TIME..FRAME_TIME: retracing=true   (total time 1s/60.000)
                 */
                if(retracing) { // Phase B ended, goto phase A
                    retracing = false;
                    if(!vgaDrawHackFlag)
                        updated = updateBasicParameters();

                    nextTimerExpiry = nextTimerExpiry + TRACE_TIME;

                    retraceTimer.setExpiry(nextTimerExpiry);
                    traceTrap.doPotentialTrap(TraceTrap.TRACE_STOP_VRETRACE_END);
                } else { // Phase A ended, goto phase B
                    retracing = true;
                    if(vgaDrawHackFlag)
                        updated = updateBasicParameters();
                    //Wait for monitor to draw. Pre-increment frame count to avoid double draw with
                    //different frame numbers.
                    updateDisplay();
                    frameNumber++;
                    updateStatus();
                    outputDevice.holdOutput(nextTimerExpiry);

                    long refresh_time = FRAME_TIME-TRACE_TIME;
                    if((frameNumber - 1) % FRAME_ALT_MOD == 0)
                        refresh_time = FRAME_TIME_ALT-TRACE_TIME;

                    nextTimerExpiry = nextTimerExpiry + refresh_time;

                    retraceTimer.setExpiry(nextTimerExpiry);
                    traceTrap.doPotentialTrap(TraceTrap.TRACE_STOP_VRETRACE_START);
                }
                break;
            }
            case 1: {
                /* Three-phase cycle:
                 *   A) 0..vrstart:     retracing=false
                 *   B) vrstart..vrend: retracing=true
                 * [ C) vrend..vtotal:  retracing=false ] if vtotal > vrend
                 */
                if(retracing) { // Phase B ended, goto phase C or A
                    retracing = false;
                    if(!vgaDrawHackFlag)
                        updated = updateBasicParameters();

                    long next_time = 0;
                    if(draw_vtotal > draw_vrend) {
                        next_time = draw_vtotal - draw_vrend; // Goto phase C
                        returningFromVretrace = true;
                    } else {
                        next_time = draw_vrstart; // Goto phase A
                        whenFrameStarted = nextTimerExpiryVGA;
                        returningFromVretrace = false;
                    }

                    nextTimerExpiryVGA = nextTimerExpiryVGA + next_time;
                    nextTimerExpiry = vgaClockToSystemClock(nextTimerExpiryVGA);

                    retraceTimer.setExpiry(nextTimerExpiry);
                    // In any case, vretrace ended here
                    traceTrap.doPotentialTrap(TraceTrap.TRACE_STOP_VRETRACE_END);
                } else {
                    /* Determine whether we are in end of phase A or phase C */
                    if(!returningFromVretrace) { // Phase A ended, goto phase B
                        retracing = true;
                        if(vgaDrawHackFlag)
                            updated = updateBasicParameters();
                        //Wait for monitor to draw. Pre-increment frame count to avoid double draw with
                        //different frame numbers.
                        updateDisplay();
                        frameNumber++;
                        updateStatus();
                        outputDevice.holdOutput(nextTimerExpiry);

                        long refresh_time = draw_vrend-draw_vrstart;

                        nextTimerExpiryVGA = nextTimerExpiryVGA + refresh_time;
                        nextTimerExpiry = vgaClockToSystemClock(nextTimerExpiryVGA);

                        retraceTimer.setExpiry(nextTimerExpiry);
                        traceTrap.doPotentialTrap(TraceTrap.TRACE_STOP_VRETRACE_START);
                    } else { // Phase C ended, goto phase A
                        long draw_time = draw_vrstart;

                        whenFrameStarted = nextTimerExpiryVGA;
                        nextTimerExpiryVGA = nextTimerExpiryVGA + draw_time;
                        nextTimerExpiry = vgaClockToSystemClock(nextTimerExpiryVGA);

                        retraceTimer.setExpiry(nextTimerExpiry);
                        returningFromVretrace = false;
                        // No trap here, this was neither the begin nor the end of vretrace
                    }
                }
            }
        }
    }


    public void acceptComponent(HardwareComponent component)
    {
        if((component instanceof PCIBus) && component.initialised())
        {
            ((PCIBus)component).registerDevice(this);
            pciRegistered = true;
        }
        if((component instanceof IOPortHandler) && component.initialised())
        {
            ((IOPortHandler)component).registerIOPortCapable(this);
            ioportRegistered = true;
        }
        if((component instanceof PhysicalAddressSpace) && component.initialised())
        {
            ((PhysicalAddressSpace)component).mapMemoryRegion(lowIORegion, 0xa0000, 0x20000);
            memoryRegistered = true;
        }
        if((component instanceof TraceTrap) && component.initialised())
        {
            traceTrap = (TraceTrap)component;
        }
        if((component instanceof Clock) && component.initialised() && component != timeSource)
        {
            timeSource = (Clock)component;
            retraceTimer = timeSource.newTimer(this);
            retraceTimer.setExpiry(nextTimerExpiry);
        }
    }

    public boolean wantsMappingUpdate()
    {
        return true;
    }

    public String toString()
    {
        return "VGA Card [Mode: " + lastScreenWidth + " x " + lastScreenHeight + "]";
    }
}
