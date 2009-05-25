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

package org.jpc.emulator.motherboard;

import org.jpc.emulator.peripheral.*;
import org.jpc.support.*;
import java.util.Calendar;
import org.jpc.emulator.*;
import java.io.*;

/**
 * MC146818 RealTime Clock emulation
 */
public class RTC extends AbstractHardwareComponent implements IOPortCapable
{
    private static final int RTC_SECONDS = 0;
    private static final int RTC_SECONDS_ALARM = 1;
    private static final int RTC_MINUTES = 2;
    private static final int RTC_MINUTES_ALARM = 3;
    private static final int RTC_HOURS = 4;
    private static final int RTC_HOURS_ALARM = 5;
    private static final int RTC_ALARM_DONT_CARE = 0xc0;

    private int memorySize;

    private static final int RTC_DAY_OF_WEEK = 6;
    private static final int RTC_DAY_OF_MONTH = 7;
    private static final int RTC_MONTH = 8;
    private static final int RTC_YEAR = 9;

    private static final int RTC_REG_EQUIPMENT_BYTE = 0x14;
    private static final int RTC_REG_IBM_CENTURY_BYTE = 0x32;
    private static final int RTC_REG_IBM_PS2_CENTURY_BYTE = 0x37;

    private static final int RTC_REG_A = 10;
    private static final int RTC_REG_B = 11;
    private static final int RTC_REG_C = 12;
    private static final int RTC_REG_D = 13;

    private static final int REG_A_UIP = 0x80;

    private static final int REG_B_SET = 0x80;
    private static final int REG_B_PIE = 0x40;
    private static final int REG_B_AIE = 0x20;
    private static final int REG_B_UIE = 0x10;

    private byte[] cmosData; //rowa
    private byte cmosIndex; //rw
    private int irq; //r
    private Calendar currentTime; //rw
    private boolean currentTimeInited;

    /* periodic timer */
    private Timer periodicTimer;
    private long nextPeriodicTime; //rw

    /* second update */
    private Timer secondTimer;
    private Timer delayedSecondTimer;
    private long nextSecondTime; //ri

    private PeriodicCallback periodicCallback;
    private SecondCallback secondCallback;
    private DelayedSecondCallback delayedSecondCallback;

    private InterruptController irqDevice;
    private Clock timeSource;
    private int ioPortBase, bootType;

    private boolean ioportRegistered;
    private boolean drivesInited;
    private boolean floppiesInited;
    private Magic magic;

    public RTC(int ioPort, int irq, int sysMemorySize)
    {
        magic = new Magic(Magic.RTC_MAGIC_V2);
        memorySize = sysMemorySize;
        bootType = -1;
        ioportRegistered = false;
        drivesInited = false;
        floppiesInited = false;

        ioPortBase = ioPort;
        this.irq = irq;
        cmosData = new byte[128];
        cmosData[RTC_REG_A] = 0x26;
        cmosData[RTC_REG_B] = 0x02;
        cmosData[RTC_REG_C] = 0x00;
        cmosData[RTC_REG_D] = (byte)0x80;

        periodicCallback = new PeriodicCallback();
        secondCallback = new SecondCallback();
        delayedSecondCallback = new DelayedSecondCallback();
    }

    public void dumpState(DataOutput output) throws IOException
    {
        magic.dumpState(output);
        output.writeBoolean(currentTimeInited);
        output.writeInt(cmosData.length);
        output.write(cmosData);
        output.writeByte(cmosIndex);
        output.writeInt(irq);
        //calendar
        output.writeInt(currentTime.get(Calendar.YEAR));
        output.writeInt(currentTime.get(Calendar.MONTH));
        output.writeInt(currentTime.get(Calendar.DAY_OF_MONTH));
        output.writeInt(currentTime.get(Calendar.HOUR_OF_DAY));
        output.writeInt(currentTime.get(Calendar.MINUTE));
        output.writeInt(currentTime.get(Calendar.SECOND));
        output.writeInt(currentTime.get(Calendar.MILLISECOND));

        output.writeLong(nextSecondTime);
        output.writeInt(ioPortBase);
        output.writeInt(bootType);
        output.writeBoolean(ioportRegistered);
        output.writeBoolean(drivesInited);
        output.writeBoolean(floppiesInited);
        //timers
        periodicTimer.dumpState(output);
        secondTimer.dumpState(output);
        delayedSecondTimer.dumpState(output);
    }

    public void dumpStatusPartial(org.jpc.support.StatusDumper output)
    {
        super.dumpStatusPartial(output);
        output.println("\tmemorySize " + memorySize + " cmosIndex " + cmosIndex + " irq " + irq);
        output.println("\tcurrentTimeInited " + currentTimeInited + " nextPeriodicTime " + nextPeriodicTime);
        output.println("\tnextSecondTime " + nextSecondTime + " ioPortBase " + ioPortBase + " bootType " + bootType);
        output.println("\tioportRegistered " + ioportRegistered + " drivesInited " + drivesInited);
        output.println("\tfloppiesInited " + floppiesInited);
        output.println("\tperiodicTimer <object #" + output.objectNumber(periodicTimer) + ">"); if(periodicTimer != null) periodicTimer.dumpStatus(output);
        output.println("\tsecondTimer <object #" + output.objectNumber(secondTimer) + ">"); if(secondTimer != null) secondTimer.dumpStatus(output);
        output.println("\tdelayedSecondTimer <object #" + output.objectNumber(delayedSecondTimer) + ">"); if(delayedSecondTimer != null) delayedSecondTimer.dumpStatus(output);
        output.println("\tperiodicCallback <object #" + output.objectNumber(periodicCallback) + ">"); if(periodicCallback != null) periodicCallback.dumpStatus(output);
        output.println("\tsecondCallback <object #" + output.objectNumber(secondCallback) + ">"); if(secondCallback != null) secondCallback.dumpStatus(output);
        output.println("\tdelayedSecondCallback <object #" + output.objectNumber(delayedSecondCallback) + ">"); if(delayedSecondCallback != null) delayedSecondCallback.dumpStatus(output);
        output.println("\tirqDevice <object #" + output.objectNumber(irqDevice) + ">"); if(irqDevice != null) irqDevice.dumpStatus(output);
        output.println("\ttimeSource <object #" + output.objectNumber(timeSource) + ">"); if(timeSource != null) timeSource.dumpStatus(output);
        output.println("\tcurrentTime:");
        output.println("\t\tyear " + currentTime.get(Calendar.YEAR));
        output.println("\t\tmonth " + currentTime.get(Calendar.MONTH));
        output.println("\t\tday " + currentTime.get(Calendar.DAY_OF_MONTH));
        output.println("\t\thour " + currentTime.get(Calendar.HOUR_OF_DAY));
        output.println("\t\tminute " + currentTime.get(Calendar.MINUTE));
        output.println("\t\tsecond " + currentTime.get(Calendar.SECOND));
        output.println("\t\tmillisecond " + currentTime.get(Calendar.MILLISECOND));
        output.printArray(cmosData, "cmosData"); 
    }
 
    public void dumpStatus(org.jpc.support.StatusDumper output)
    {
        if(output.dumped(this))
            return;

        output.println("#" + output.objectNumber(this) + ": RTC:");
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
        output.dumpInt(memorySize);
        output.dumpArray(cmosData);
        output.dumpByte(cmosIndex);
        output.dumpInt(irq);
        output.dumpInt(currentTime.get(Calendar.YEAR));
        output.dumpInt(currentTime.get(Calendar.MONTH));
        output.dumpInt(currentTime.get(Calendar.DAY_OF_MONTH));
        output.dumpInt(currentTime.get(Calendar.HOUR_OF_DAY));
        output.dumpInt(currentTime.get(Calendar.MINUTE));
        output.dumpInt(currentTime.get(Calendar.SECOND));
        output.dumpInt(currentTime.get(Calendar.MILLISECOND));
        output.dumpBoolean(currentTimeInited);
        output.dumpObject(periodicTimer);
        output.dumpLong(nextPeriodicTime);
        output.dumpObject(secondTimer);
        output.dumpObject(delayedSecondTimer);
        output.dumpLong(nextSecondTime);
        output.dumpObject(periodicCallback);
        output.dumpObject(secondCallback);
        output.dumpObject(delayedSecondCallback);
        output.dumpObject(irqDevice);
        output.dumpObject(timeSource);
        output.dumpInt(ioPortBase);
        output.dumpInt(bootType);
        output.dumpBoolean(ioportRegistered);
        output.dumpBoolean(drivesInited);
        output.dumpBoolean(floppiesInited);
    }

    public void loadState(DataInput input) throws IOException
    {
        int year, month, day, hour, minute, second, millisecond;

        magic.loadState(input);
        currentTimeInited = input.readBoolean();
        ioportRegistered = false;
        int len = input.readInt();
        input.readFully(cmosData,0,len);
        cmosIndex = input.readByte();
        irq = input.readInt();
        //calendar
        year = input.readInt();
        month = input.readInt();
        day = input.readInt();
        hour = input.readInt();
        minute = input.readInt();
        second = input.readInt();
        millisecond = input.readInt();
        currentTime.set(year, month, day, hour, minute, second);
        currentTime.set(Calendar.MILLISECOND, millisecond);

        nextSecondTime = input.readLong();
        ioPortBase = input.readInt();
        bootType = input.readInt();
        ioportRegistered = input.readBoolean();
        drivesInited = input.readBoolean();
        floppiesInited = input.readBoolean();
        //timers
        periodicTimer = timeSource.newTimer(periodicCallback);
        secondTimer = timeSource.newTimer(secondCallback);
        delayedSecondTimer = timeSource.newTimer(delayedSecondCallback);
        periodicTimer.loadState(input);
        secondTimer.loadState(input);
        delayedSecondTimer.loadState(input);
    }

    static final long scale64(long input, int multiply, int divide)
    {
        //return (BigInteger.valueOf(input).multiply(BigInteger.valueOf(multiply)).divide(BigInteger.valueOf(divide))).longValue();

        long rl = (0xffffffffl & input) * multiply;
        long rh = (input >>> 32) * multiply;

        rh += (rl >> 32);

        long resultHigh = 0xffffffffl & (rh / divide);
        long resultLow = 0xffffffffl & ((((rh % divide) << 32) + (rl & 0xffffffffl)) / divide);

        return (resultHigh << 32) | resultLow;
    }

    public void init()
    {
        int val;
        if(!currentTimeInited) {
            Calendar now = Calendar.getInstance();
            this.setTime(now);
            val = this.toBCD(now.get(Calendar.YEAR) / 100);
            currentTimeInited = true;
        } else {
            val = this.toBCD(currentTime.get(Calendar.YEAR) / 100);
        }
        cmosData[RTC_REG_IBM_CENTURY_BYTE] = (byte)val;
        cmosData[RTC_REG_IBM_PS2_CENTURY_BYTE] = (byte)val;

        /* memory size */
        val = 640; /* base memory in K */
        cmosData[0x15] = (byte)val;
        cmosData[0x16] = (byte)(val >>> 8);

        int ramSize = memorySize;
        val = (ramSize / 1024) - 1024;
        if (val > 65535) val = 65535;
        cmosData[0x17] = (byte)val;
        cmosData[0x18] = (byte)(val >>> 8);
        cmosData[0x30] = (byte)val;
        cmosData[0x31] = (byte)(val >>> 8);

        if (ramSize > (16 * 1024 * 1024)) {
            val = (ramSize / 65536) - ((16 * 1024 * 1024) / 65536);
        } else {
            val = 0;
        }
        if (val > 65535) val = 65535;
        cmosData[0x34] = (byte)val;
        cmosData[0x35] = (byte)(val >>> 8);

        switch(bootType)
        {
        case DriveSet.FLOPPY_BOOT:
            cmosData[0x3d] = (byte)0x01; /* floppy boot */
            break;
        default:
        case DriveSet.HARD_DRIVE_BOOT:
            cmosData[0x3d] = (byte)0x02; /* hard drive boot */
            break;
        case DriveSet.CD_BOOT:
            cmosData[0x3d] = (byte)0x03; /* CD-ROM boot */
            break;
        }
    }

    public void cmosInitHD(DriveSet drives)
    {
        BlockDevice drive0 = drives.getHardDrive(0);
        BlockDevice drive1 = drives.getHardDrive(1);


        cmosData[0x12] = (byte)(((drive0 != null) ? 0xf0 : 0) | ((drive1 != null) ? 0x0f : 0));

        if (drive0 != null) {
            cmosData[0x19] = (byte)47;
            cmosData[0x1b] = (byte)drive0.cylinders();
            cmosData[0x1b + 1] = (byte)(drive0.cylinders() >>> 8);
            cmosData[0x1b + 2] = (byte)drive0.heads();
            cmosData[0x1b + 3] = (byte)0xff;
            cmosData[0x1b + 4] = (byte)0xff;
            cmosData[0x1b + 5] = (byte)(0xc0 | ((drive0.heads() > 8) ? 0x8 : 0));
            cmosData[0x1b + 6] = (byte)drive0.cylinders();
            cmosData[0x1b + 7] = (byte)(drive0.cylinders() >>> 8);
            cmosData[0x1b + 8] = (byte)drive0.sectors();
        }
        if (drive1 != null) {
            cmosData[0x1a] = (byte)47;
            cmosData[0x24] = (byte)drive1.cylinders();
            cmosData[0x24 + 1] = (byte)(drive1.cylinders() >>> 8);
            cmosData[0x24 + 2] = (byte)drive1.heads();
            cmosData[0x24 + 3] = (byte)0xff;
            cmosData[0x24 + 4] = (byte)0xff;
            cmosData[0x24 + 5] = (byte)(0xc0 | ((drive1.heads() > 8) ? 0x8 : 0));
            cmosData[0x24 + 6] = (byte)drive1.cylinders();
            cmosData[0x24 + 7] = (byte)(drive1.cylinders() >>> 8);
            cmosData[0x24 + 8] = (byte)drive1.sectors();
        }
        int value = 0;
        for (int i = 0; i < 4; i++) {
            if (drives.getHardDrive(i) != null) {
                int translation;
                if ((drives.getHardDrive(i).cylinders() <= 1024) &&
                    (drives.getHardDrive(i).heads() <= 16) &&
                    (drives.getHardDrive(i).sectors() <= 63)) {
                    /* No Translation. */
                    translation = 0;
                } else {
                    /* LBA Translation */
                    translation = 1;
                }
                value |= translation << (i * 2);
            }
        }
        cmosData[0x39] = (byte)value;
    }

    public void cmosInitFloppy(FloppyController fdc)
    {
        int val = (cmosGetFDType(fdc, 0) << 4) | cmosGetFDType(fdc, 1);
        cmosData[0x10] = (byte)val;

        int num = 0;
        val = 0;
        if (fdc.getDriveType(0) < 3)
            num++;
        if (fdc.getDriveType(1) < 3)
            num++;
        switch (num) {
        case 0:
            break;
        case 1:
            val |= 0x01;
            break;
        case 2:
            val |= 0x41;
            break;
        }
        val |= 0x02; // Have FPU
        val |= 0x04; // Have PS2 Mouse
        cmosData[RTC_REG_EQUIPMENT_BYTE] = (byte)val;
    }
    private int cmosGetFDType(FloppyController fdc, int drive)
    {
        switch (fdc.getDriveType(drive)) {
        case 0:
            return 4;
        case 1:
            return 5;
        case 2:
            return 2;
        default:
            return 0;
        }
    }
    public int[] ioPortsRequested()
    {
        int base = ioPortBase;
        return new int[]{base, base+1};
    }
    public int ioPortReadByte(int address)
    {
        return 0xff & cmosIOPortRead(address);
    }
    public int ioPortReadWord(int address)
    {
        return (0xff & ioPortReadByte(address)) | (0xff00 & (ioPortReadByte(address + 1) << 8));
    }
    public int ioPortReadLong(int address)
    {
        return (0xffff & ioPortReadWord(address)) | (0xffff0000 & (ioPortReadWord(address + 2) << 16));
    }

    public void ioPortWriteByte(int address, int data)
    {
        cmosIOPortWrite(address, 0xff & data);
    }
    public void ioPortWriteWord(int address, int data)
    {
        this.ioPortWriteByte(address, data);
        this.ioPortWriteByte(address + 1, data >> 8);
    }
    public void ioPortWriteLong(int address, int data)
    {
        this.ioPortWriteWord(address, data);
        this.ioPortWriteWord(address + 2, data >> 16);
    }

    private class PeriodicCallback extends AbstractHardwareComponent
    {
        private Magic magic2;

        public void dumpSR(org.jpc.support.SRDumper output) throws IOException
        {
            if(output.dumped(this))
                return;
            dumpSRPartial(output);
            output.endObject();
        }

        public void dumpSRPartial(org.jpc.support.SRDumper output) throws IOException
        {
            output.dumpOuter(RTC.this);
            super.dumpSRPartial(output);
        }

        public void dumpStatusPartial(org.jpc.support.StatusDumper output)
        {
            super.dumpStatusPartial(output);
            output.println("outer object <object #" + output.objectNumber(RTC.this) + ">");
            RTC.this.dumpStatus(output);
        }

        public void dumpStatus(org.jpc.support.StatusDumper output)
        {
            if(output.dumped(this))
                return;

            output.println("#" + output.objectNumber(this) + ": PeriodicCallback:");
            dumpStatusPartial(output);
            output.endObject();
        }

        public PeriodicCallback() {
            magic2 = new Magic(Magic.PERIODIC_CALLBACK_MAGIC_V1);
        }

        public void timerCallback()
        {
            RTC.this.periodicUpdate();
        }

        public boolean initialised() {return true;}
        public void acceptComponent(HardwareComponent component){}
        public void reset(){}
        public void dumpState(DataOutput output) throws IOException
        {
            magic2.dumpState(output);
        }
        public void loadState(DataInput input) throws IOException
        {
            magic2.loadState(input);
        }
    }

    private class SecondCallback extends AbstractHardwareComponent
    {
        private Magic magic2;

        public void dumpSR(org.jpc.support.SRDumper output) throws IOException
        {
            if(output.dumped(this))
                return;
            dumpSRPartial(output);
            output.endObject();
        }

        public void dumpSRPartial(org.jpc.support.SRDumper output) throws IOException
        {
            output.dumpOuter(RTC.this);
            super.dumpSRPartial(output);
        }

        public void dumpStatusPartial(org.jpc.support.StatusDumper output)
        {
            super.dumpStatusPartial(output);
            output.println("outer object <object #" + output.objectNumber(RTC.this) + ">");
            RTC.this.dumpStatus(output);
        }

        public void dumpStatus(org.jpc.support.StatusDumper output)
        {
            if(output.dumped(this))
                return;

            output.println("#" + output.objectNumber(this) + ": SecondCallback:");
            dumpStatusPartial(output);
            output.endObject();
        }

        public SecondCallback() {
            magic2 = new Magic(Magic.SECOND_CALLBACK_MAGIC_V1);
        }

        public void timerCallback()
        {
            RTC.this.secondUpdate();
        }

        public boolean initialised() {return true;}
        public void acceptComponent(HardwareComponent component){}
        public void reset(){}
        public void dumpState(DataOutput output) throws IOException
        {
            magic2.dumpState(output);
        }
        public void loadState(DataInput input) throws IOException
        {
            magic2.loadState(input);
        }
    }

    private class DelayedSecondCallback extends AbstractHardwareComponent
    {
        private Magic magic2;

        public void dumpSR(org.jpc.support.SRDumper output) throws IOException
        {
            if(output.dumped(this))
                return;
            dumpSRPartial(output);
            output.endObject();
        }

        public void dumpSRPartial(org.jpc.support.SRDumper output) throws IOException
        {
            output.dumpOuter(RTC.this);
            super.dumpSRPartial(output);
        }

        public void dumpStatusPartial(org.jpc.support.StatusDumper output)
        {
            super.dumpStatusPartial(output);
            output.println("outer object <object #" + output.objectNumber(RTC.this) + ">");
            RTC.this.dumpStatus(output);
        }

        public void dumpStatus(org.jpc.support.StatusDumper output)
        {
            if(output.dumped(this))
                return;

            output.println("#" + output.objectNumber(this) + ": DelayedSecondCallback:");
            dumpStatusPartial(output);
            output.endObject();
        }

        public DelayedSecondCallback() {
            magic2 = new Magic(Magic.DELAYED_SECOND_CALLBACK_MAGIC_V1);
        }

        public void timerCallback()
        {
            RTC.this.delayedSecondUpdate();
        }

        public boolean initialised() {return true;}
        public void acceptComponent(HardwareComponent component){}
        public void reset(){}
        public void dumpState(DataOutput output) throws IOException
        {
            magic2.dumpState(output);
        }
        public void loadState(DataInput input) throws IOException
        {
            magic2.loadState(input);
        }
    }

    private void periodicUpdate()
    {
        this.timerUpdate(nextPeriodicTime);
        cmosData[RTC_REG_C] |= 0xc0;
        irqDevice.setIRQ(irq, 1);
    }

    private void secondUpdate()
    {
        if ((cmosData[RTC_REG_A] & 0x70) != 0x20) {
            nextSecondTime += timeSource.getTickRate();
            secondTimer.setExpiry(nextSecondTime);
        } else {
            this.nextSecond();

            if (0 == (cmosData[RTC_REG_B] & REG_B_SET)) /* update in progress bit */
                cmosData[RTC_REG_A] |= REG_A_UIP;

            /* should be 244us = 8 / 32768 second, but currently the timers do not have the necessary resolution. */
            long delay = (timeSource.getTickRate() * 1) / 100;
            if (delay < 1)
                delay = 1;
            delayedSecondTimer.setExpiry(nextSecondTime + delay);
        }
    }

    private void delayedSecondUpdate()
    {
        if (0 == (cmosData[RTC_REG_B] & REG_B_SET))
            this.timeToMemory();

        /* check alarm */
        if (0 != (cmosData[RTC_REG_B] & REG_B_AIE)) {
            if (((cmosData[RTC_SECONDS_ALARM] & 0xc0) == 0xc0 ||
                 cmosData[RTC_SECONDS_ALARM] == currentTime.get(Calendar.SECOND)) &&
                ((cmosData[RTC_MINUTES_ALARM] & 0xc0) == 0xc0 ||
                 cmosData[RTC_MINUTES_ALARM] == currentTime.get(Calendar.MINUTE)) &&
                ((cmosData[RTC_HOURS_ALARM] & 0xc0) == 0xc0 ||
                 cmosData[RTC_HOURS_ALARM] == currentTime.get(Calendar.HOUR_OF_DAY))) {

                cmosData[RTC_REG_C] |= 0xa0;
                irqDevice.setIRQ(irq, 1);
            }
        }

        /* update ended interrupt */
        if (0 != (cmosData[RTC_REG_B] & REG_B_UIE)) {
            cmosData[RTC_REG_C] |= 0x90;
            irqDevice.setIRQ(irq, 1);
        }

        /* clear update in progress bit */
        cmosData[RTC_REG_A] &= ~REG_A_UIP;
        nextSecondTime += timeSource.getTickRate();
        secondTimer.setExpiry(nextSecondTime);
    }

    private void timerUpdate(long currentTime)
    {
        int periodCode = cmosData[RTC_REG_A] & 0x0f;
        if ((periodCode != 0) && (0 != (cmosData[RTC_REG_B] & REG_B_PIE)))
        {
            if (periodCode <= 2)
                periodCode += 7;
            /* period in 32 kHz cycles */
            int period = 1 << (periodCode -1);
            /* compute 32 kHz clock */
            long currentClock = scale64(currentTime, 32768, (int)timeSource.getTickRate());
            long nextIRQClock = (currentClock & ~(period - 1)) + period;
            nextPeriodicTime = scale64(nextIRQClock, (int)timeSource.getTickRate(), 32768) + 1;
            periodicTimer.setExpiry(nextPeriodicTime);
        }
        else
            periodicTimer.setStatus(false);
    }

    private void nextSecond()
    {
        //currentTime = Calendar.getInstance();
        currentTime.add(Calendar.SECOND,1);
    }

    private void cmosIOPortWrite(int address, int data)
    {
        if ((address & 1) == 0) {
            cmosIndex = (byte)(data & 0x7f);
        } else {
            switch(this.cmosIndex) {
            case RTC_SECONDS_ALARM:
            case RTC_MINUTES_ALARM:
            case RTC_HOURS_ALARM:
                /* XXX: not supported */
                cmosData[this.cmosIndex] = (byte)data;
                break;
            case RTC_SECONDS:
            case RTC_MINUTES:
            case RTC_HOURS:
            case RTC_DAY_OF_WEEK:
            case RTC_DAY_OF_MONTH:
            case RTC_MONTH:
            case RTC_YEAR:
                cmosData[this.cmosIndex] = (byte)data;
                /* if in set mode, do not update the time */
                if (0 == (cmosData[RTC_REG_B] & REG_B_SET))
                    this.memoryToTime();
                break;
            case RTC_REG_A:
                /* UIP bit is read only */
                cmosData[RTC_REG_A] = (byte)((data & ~REG_A_UIP) | (cmosData[RTC_REG_A] & REG_A_UIP));
                this.timerUpdate(timeSource.getTime());
                break;
            case RTC_REG_B:
                if (0 != (data & REG_B_SET)) {
                    /* set mode: reset UIP mode */
                    cmosData[RTC_REG_A] &= ~REG_A_UIP;
                    data &= ~REG_B_UIE;
                } else {
                    /* if disabling set mode, update the time */
                    if (0 != (cmosData[RTC_REG_B] & REG_B_SET))
                        this.memoryToTime();
                }
                cmosData[RTC_REG_B] = (byte)data;
                this.timerUpdate(timeSource.getTime());
                break;
            case RTC_REG_C:
            case RTC_REG_D:
                /* cannot write to them */
                break;
            default:
                cmosData[this.cmosIndex] = (byte)data;
                break;
            }
        }
    }

    private int cmosIOPortRead(int address)
    {
        if ((address & 1) == 0)
            return 0xff;
        else {
            switch(this.cmosIndex)  {
            case RTC_SECONDS:
            case RTC_MINUTES:
            case RTC_HOURS:
            case RTC_DAY_OF_WEEK:
            case RTC_DAY_OF_MONTH:
            case RTC_MONTH:
            case RTC_YEAR:
                return cmosData[this.cmosIndex];
            case RTC_REG_A:
                return cmosData[this.cmosIndex];
            case RTC_REG_C:
                int ret = cmosData[this.cmosIndex];
                irqDevice.setIRQ(irq, 0);
                cmosData[RTC_REG_C] = (byte)0x00;
                return ret;
            default:
                return cmosData[this.cmosIndex];
            }
        }
    }

    private void setTime(Calendar date)
    {
        this.currentTime = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        this.currentTime.setTime(date.getTime());
        this.timeToMemory();
    }

    private void memoryToTime()
    {
        currentTime.set(Calendar.SECOND, this.fromBCD(cmosData[RTC_SECONDS]));
        currentTime.set(Calendar.MINUTE, this.fromBCD(cmosData[RTC_MINUTES]));
        currentTime.set(Calendar.HOUR_OF_DAY, this.fromBCD(cmosData[RTC_HOURS] & 0x7f));
        if (0 == (cmosData[RTC_REG_B] & 0x02) && 0 != (cmosData[RTC_HOURS] & 0x80))
            currentTime.add(Calendar.HOUR_OF_DAY, 12);

        currentTime.set(Calendar.DAY_OF_WEEK, this.fromBCD(cmosData[RTC_DAY_OF_WEEK]));
        currentTime.set(Calendar.DAY_OF_MONTH, this.fromBCD(cmosData[RTC_DAY_OF_MONTH]));
        currentTime.set(Calendar.MONTH, this.fromBCD(cmosData[RTC_MONTH]) - 1);
        currentTime.set(Calendar.YEAR, this.fromBCD(cmosData[RTC_YEAR]) + 2000); //is this offset correct?
    }

    private void timeToMemory()
    {
        cmosData[RTC_SECONDS] = (byte)this.toBCD(currentTime.get(Calendar.SECOND));
        cmosData[RTC_MINUTES] = (byte)this.toBCD(currentTime.get(Calendar.MINUTE));

        if (0 != (cmosData[RTC_REG_B] & 0x02)) /* 24 hour format */
            cmosData[RTC_HOURS] = (byte)this.toBCD(currentTime.get(Calendar.HOUR_OF_DAY));
        else { /* 12 hour format */
            cmosData[RTC_HOURS] = (byte)this.toBCD(currentTime.get(Calendar.HOUR));
            if(currentTime.get(Calendar.AM_PM) == Calendar.PM)
                cmosData[RTC_HOURS] |= 0x80;
        }

        cmosData[RTC_DAY_OF_WEEK] = (byte)this.toBCD(currentTime.get(Calendar.DAY_OF_WEEK));
        cmosData[RTC_DAY_OF_MONTH] = (byte)this.toBCD(currentTime.get(Calendar.DAY_OF_MONTH));
        cmosData[RTC_MONTH] = (byte)this.toBCD(currentTime.get(Calendar.MONTH) + 1);
        cmosData[RTC_YEAR] = (byte)this.toBCD(currentTime.get(Calendar.YEAR) % 100);
    }

    private int toBCD(int a) //Binary Coded Decimal
    {
        if (0 != (cmosData[RTC_REG_B] & 0x04))
            return a;
        else
            return ((a / 10) << 4) | (a % 10);
    }

    private int fromBCD(int a) //Binary Coded Decimal
    {
        if (0 != (cmosData[RTC_REG_B] & 0x04))
            return a;
        else
            return ((a >> 4) * 10) + (a & 0x0f);
    }

    public boolean initialised()
    {
        return ((irqDevice != null) && (timeSource != null) && ioportRegistered && drivesInited && floppiesInited && (bootType >= 0));
    }

    public void reset()
    {
        irqDevice = null;
        timeSource = null;
        ioportRegistered = false;
        drivesInited = false;
        floppiesInited = false;
        bootType = -1;

        cmosData = new byte[128];
        cmosData[RTC_REG_A] = 0x26;
        cmosData[RTC_REG_B] = 0x02;
        cmosData[RTC_REG_C] = 0x00;
        cmosData[RTC_REG_D] = (byte)0x80;

        periodicCallback = new PeriodicCallback();
        secondCallback = new SecondCallback();
        delayedSecondCallback = new DelayedSecondCallback();
    }

    public boolean updated()
    {
        return (irqDevice.updated() && timeSource.updated() && ioportRegistered);
    }

    public void updateComponent(HardwareComponent component)
    {
        if ((component instanceof IOPortHandler) && component.updated())
        {
            ((IOPortHandler)component).registerIOPortCapable(this);
            ioportRegistered = true;
        }
    }

    public void acceptComponent(HardwareComponent component)
    {
        if ((component instanceof InterruptController)
            && component.initialised())
            irqDevice = (InterruptController)component;
        if ((component instanceof Clock)
            && component.initialised())
            timeSource = (Clock)component;
        if ((component instanceof IOPortHandler)
            && component.initialised()) {
            ((IOPortHandler)component).registerIOPortCapable(this);
            ioportRegistered = true;
        }
        if ((component instanceof DriveSet)
            && component.initialised()) {
            this.cmosInitHD((DriveSet)component);
            drivesInited = true;
        }
        if ((component instanceof FloppyController)
            && component.initialised()) {
            this.cmosInitFloppy((FloppyController)component);
            floppiesInited = true;
        }
        if (component instanceof DriveSet)
            bootType = ((DriveSet) component).getBootType();

        if (this.initialised())
        {
            init();

            periodicTimer = timeSource.newTimer(periodicCallback);
            secondTimer = timeSource.newTimer(secondCallback);
            delayedSecondTimer = timeSource.newTimer(delayedSecondCallback);

            nextSecondTime = timeSource.getTime() + (99 * timeSource.getTickRate())/100;
            delayedSecondTimer.setExpiry(nextSecondTime);
        }
    }

    public String toString()
    {
        return "MC146818 RealTime Clock";
    }
}


