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

package org.jpc.emulator.peripheral;

import java.io.*;
import java.util.List;
import java.util.ArrayList;

import org.jpc.emulator.motherboard.*;
import org.jpc.emulator.memory.*;
import org.jpc.emulator.processor.Processor;
import org.jpc.emulator.*;

/**
 *
 * @author Chris Dennis
 */
public class Keyboard extends AbstractHardwareComponent implements IOPortCapable, EventDispatchTarget, TimerResponsive
{
    /* Keyboard Controller Commands */
    private static final byte KBD_CCMD_READ_MODE = (byte)0x20; /* Read mode bits */
    private static final byte KBD_CCMD_WRITE_MODE = (byte)0x60; /* Write mode bits */
    private static final byte KBD_CCMD_MOUSE_DISABLE = (byte)0xA7; /* Disable mouse interface */
    private static final byte KBD_CCMD_MOUSE_ENABLE = (byte)0xA8; /* Enable mouse interface */
    private static final byte KBD_CCMD_TEST_MOUSE = (byte)0xA9; /* Mouse interface test */
    private static final byte KBD_CCMD_SELF_TEST = (byte)0xAA; /* Controller self test */
    private static final byte KBD_CCMD_KBD_TEST = (byte)0xAB; /* Keyboard interface test */
    private static final byte KBD_CCMD_KBD_DISABLE = (byte)0xAD; /* Keyboard interface disable */
    private static final byte KBD_CCMD_KBD_ENABLE = (byte)0xAE; /* Keyboard interface enable */
    private static final byte KBD_CCMD_READ_INPORT = (byte)0xC0; /* read input port */
    private static final byte KBD_CCMD_READ_OUTPORT = (byte)0xD0; /* read output port */
    private static final byte KBD_CCMD_WRITE_OUTPORT = (byte)0xD1; /* write output port */
    private static final byte KBD_CCMD_WRITE_OBUF = (byte)0xD2;
    private static final byte KBD_CCMD_WRITE_AUX_OBUF = (byte)0xD3; /* Write to output buffer as if initiated by the auxiliary device */
    private static final byte KBD_CCMD_WRITE_MOUSE = (byte)0xD4; /* Write the following byte to the mouse */
    private static final byte KBD_CCMD_DISABLE_A20 = (byte)0xDD; /* HP vectra only ? */
    private static final byte KBD_CCMD_ENABLE_A20 = (byte)0xDF; /* HP vectra only ? */
    private static final byte KBD_CCMD_RESET = (byte)0xFE;

    /* Keyboard Commands */
    private static final byte KBD_CMD_SET_LEDS = (byte)0xED;; /* Set keyboard leds */
    private static final byte KBD_CMD_ECHO = (byte)0xEE;
    private static final byte KBD_CMD_GET_ID = (byte)0xF2; /* get keyboard ID */
    private static final byte KBD_CMD_SET_RATE = (byte)0xF3; /* Set typematic rate */
    private static final byte KBD_CMD_ENABLE = (byte)0xF4; /* Enable scanning */
    private static final byte KBD_CMD_RESET_DISABLE = (byte)0xF5; /* reset and disable scanning */
    private static final byte KBD_CMD_RESET_ENABLE = (byte)0xF6; /* reset and enable scanning */
    private static final byte KBD_CMD_RESET = (byte)0xFF; /* Reset */

    /* Keyboard Replies */
    private static final byte KBD_REPLY_POR = (byte)0xAA; /* Power on reset */
    private static final byte KBD_REPLY_ACK = (byte)0xFA; /* Command ACK */
    private static final byte KBD_REPLY_RESEND = (byte)0xFE; /* Command NACK, send the cmd again */

    /* Status Register Bits */
    private static final byte KBD_STAT_OBF = (byte)0x01; /* Keyboard output buffer full */
    private static final byte KBD_STAT_SELFTEST = (byte)0x04; /* Self test successful */
    private static final byte KBD_STAT_CMD = (byte)0x08; /* Last write was a command write (0=data) */
    private static final byte KBD_STAT_UNLOCKED = (byte)0x10; /* Zero if keyboard locked */
    private static final byte KBD_STAT_MOUSE_OBF = (byte)0x20; /* Mouse output buffer full */

    /* Controller Mode Register Bits */
    private static final int KBD_MODE_KBD_INT = 0x01; /* Keyboard data generate IRQ1 */
    private static final int KBD_MODE_MOUSE_INT = 0x02; /* Mouse data generate IRQ12 */
    private static final int KBD_MODE_DISABLE_KBD = 0x10; /* Disable keyboard interface */
    private static final int KBD_MODE_DISABLE_MOUSE = 0x20; /* Disable mouse interface */

    /* Mouse Commands */
    private static final byte AUX_SET_SCALE11 = (byte)0xE6; /* Set 1:1 scaling */
    private static final byte AUX_SET_SCALE21 = (byte)0xE7; /* Set 2:1 scaling */
    private static final byte AUX_SET_RES = (byte)0xE8; /* Set resolution */
    private static final byte AUX_GET_SCALE = (byte)0xE9; /* Get scaling factor */
    private static final byte AUX_SET_STREAM = (byte)0xEA; /* Set stream mode */
    private static final byte AUX_POLL = (byte)0xEB; /* Poll */
    private static final byte AUX_RESET_WRAP = (byte)0xEC; /* Reset wrap mode */
    private static final byte AUX_SET_WRAP = (byte)0xEE; /* Set wrap mode */
    private static final byte AUX_SET_REMOTE = (byte)0xF0; /* Set remote mode */
    private static final byte AUX_GET_TYPE = (byte)0xF2; /* Get type */
    private static final byte AUX_SET_SAMPLE = (byte)0xF3; /* Set sample rate */
    private static final byte AUX_ENABLE_DEV = (byte)0xF4; /* Enable aux device */
    private static final byte AUX_DISABLE_DEV = (byte)0xF5; /* Disable aux device */
    private static final byte AUX_SET_DEFAULT = (byte)0xF6;
    private static final byte AUX_RESET = (byte)0xFF; /* Reset aux device */
    private static final byte AUX_ACK = (byte)0xFA; /* Command byte ACK. */

    private static final byte MOUSE_STATUS_REMOTE = (byte)0x40;
    private static final byte MOUSE_STATUS_ENABLED = (byte)0x20;
    private static final byte MOUSE_STATUS_SCALE21 = (byte)0x10;

    private static final int MOUSE_TYPE = 0; /* 0 = PS2, 3 = IMPS/2, 4 = IMEX */

    private static final int KBD_QUEUE_SIZE = 256;

    private static final long CLOCKING_MODULO = 66666;

    //Instance Variables
    private KeyboardQueue queue;
    private int modifierFlags;

    private byte commandWrite;
    private byte status;
    private int mode;
    /* keyboard state */
    private int keyboardWriteCommand;
    private boolean keyboardScanEnabled;
    /* mouse state */ //Split this off?
    private int mouseWriteCommand;
    private int mouseStatus;
    private int mouseResolution;
    private int mouseSampleRate;
    private boolean mouseWrap;
    private int mouseDetectState;
    private int mouseDx;
    private int mouseDy;
    private int mouseDz;
    private int mouseHoldDx;
    private int mouseHoldDy;
    private int mouseHoldDz;
    private int mouseButtons;

    private boolean ioportRegistered;

    private InterruptController irqDevice;
    private Processor cpu;
    private PhysicalAddressSpace physicalAddressSpace;
    private LinearAddressSpace linearAddressSpace;
    private int ledStatus;
    private Clock clock;
    private Timer mouseStreamTimer;
    private long nextMouseStreamSend;
    private int lastMouseButtons;

    private EventRecorder recorder;      //Not saved.
    private long keyboardTimeBound;      //Not saved.
    private int modifierFlags2;          //Not saved.
    private boolean[] keyStatus;         //Not saved.
    private boolean[] execKeyStatus;     //Not saved.
    private List<KeyboardStatusListener> listeners;   //Not saved.
    private boolean suppressListened;    //Not saved.
    private int mouseButtonStatus;       //Not saved.

    public void dumpStatusPartial(StatusDumper output)
    {
        output.println("\tcommandWrite " + commandWrite + " status " + status + " mode " + mode);
        output.println("\tkeyboardWriteCommand " + keyboardWriteCommand + " keyboardScanEnabled " + keyboardScanEnabled);
        output.println("\tmouseWriteCommand " + mouseWriteCommand + " mouseStatus " + mouseStatus);
        output.println("\tmouseResolution " + mouseResolution + " mouseSampleRate " + mouseSampleRate);
        output.println("\tmouseWrap " + mouseWrap + " mouseDetectState " + mouseDetectState);
        output.println("\tmouseDx " + mouseDx + " mouseDy " + mouseDy + " mouseDz " + mouseDz);
        output.println("\tmouseHoldDx " + mouseHoldDx + " mouseHoldDy " + mouseHoldDy + " mouseHoldDz " + mouseHoldDz);
        output.println("\tmouseButtons " + mouseButtons + " ioportRegistered " + ioportRegistered);
        output.println("\tmodifierFlags " + modifierFlags);
        output.println("\tnextMouseStreamSend " + nextMouseStreamSend);
        output.println("\tlastMouseButtons " + lastMouseButtons);
        output.println("\tqueue <object #" + output.objectNumber(queue) + ">"); if(queue != null) queue.dumpStatus(output);
        output.println("\tirqDevice <object #" + output.objectNumber(irqDevice) + ">"); if(irqDevice != null) irqDevice.dumpStatus(output);
        output.println("\tcpu <object #" + output.objectNumber(cpu) + ">"); if(cpu != null) cpu.dumpStatus(output);
        output.println("\tphysicalAddressSpace <object #" + output.objectNumber(physicalAddressSpace) + ">"); if(physicalAddressSpace != null) physicalAddressSpace.dumpStatus(output);
        output.println("\tlinearAddressSpace <object #" + output.objectNumber(linearAddressSpace) + ">"); if(linearAddressSpace != null) linearAddressSpace.dumpStatus(output);
        output.println("\tclock <object #" + output.objectNumber(clock) + ">"); if(clock != null) clock.dumpStatus(output);
        output.println("\tmouseStreamTimer <object #" + output.objectNumber(mouseStreamTimer) + ">"); if(mouseStreamTimer != null) mouseStreamTimer.dumpStatus(output);
    }

    public void dumpStatus(StatusDumper output)
    {
        if(output.dumped(this))
            return;

        output.println("#" + output.objectNumber(this) + ": Keyboard:");
        dumpStatusPartial(output);
        output.endObject();
    }

    public void dumpSRPartial(SRDumper output) throws IOException
    {
        super.dumpSRPartial(output);
        output.dumpObject(queue);
        output.dumpInt(modifierFlags);
        output.dumpByte(commandWrite);
        output.dumpByte(status);
        output.dumpInt(mode);
        output.dumpInt(keyboardWriteCommand);
        output.dumpBoolean(keyboardScanEnabled);
        output.dumpInt(mouseWriteCommand);
        output.dumpInt(mouseStatus);
        output.dumpInt(mouseResolution);
        output.dumpInt(mouseSampleRate);
        output.dumpBoolean(mouseWrap);
        output.dumpInt(mouseDetectState);
        output.dumpInt(mouseDx);
        output.dumpInt(mouseDy);
        output.dumpInt(mouseDz);
        output.dumpInt(mouseButtons);
        output.dumpBoolean(ioportRegistered);
        output.dumpObject(irqDevice);
        output.dumpObject(cpu);
        output.dumpObject(physicalAddressSpace);
        output.dumpObject(linearAddressSpace);
        output.dumpInt(ledStatus);
        output.dumpObject(clock);
        output.dumpObject(mouseStreamTimer);
        output.dumpLong(nextMouseStreamSend);
        output.dumpInt(lastMouseButtons);
        output.dumpInt(mouseHoldDx);
        output.dumpInt(mouseHoldDy);
        output.dumpInt(mouseHoldDz);
    }

    public Keyboard(SRLoader input) throws IOException
    {
        super(input);
        queue = (KeyboardQueue)input.loadObject();
        modifierFlags = input.loadInt();
        commandWrite = input.loadByte();
        status = input.loadByte();
        mode = input.loadInt();
        keyboardWriteCommand = input.loadInt();
        keyboardScanEnabled = input.loadBoolean();
        mouseWriteCommand = input.loadInt();
        mouseStatus = input.loadInt();
        mouseResolution = input.loadInt();
        mouseSampleRate = input.loadInt();
        mouseWrap = input.loadBoolean();
        mouseDetectState = input.loadInt();
        mouseDx = input.loadInt();
        mouseDy = input.loadInt();
        mouseDz = input.loadInt();
        mouseButtons = input.loadInt();
        ioportRegistered = input.loadBoolean();
        irqDevice = (InterruptController)input.loadObject();
        cpu = (Processor)input.loadObject();
        physicalAddressSpace = (PhysicalAddressSpace)input.loadObject();
        linearAddressSpace = (LinearAddressSpace)input.loadObject();
        keyStatus = new boolean[256];
        execKeyStatus = new boolean[256];
        ledStatus = -1;
        clock = null;
        mouseStreamTimer = null;
        nextMouseStreamSend = -1;
        mouseHoldDx = mouseHoldDy = mouseHoldDz = 0;
        lastMouseButtons = mouseButtons;
        listeners = new ArrayList<KeyboardStatusListener>();
        if(!input.objectEndsHere())
            ledStatus = input.loadInt();
        if(!input.objectEndsHere()) {
            clock = (Clock)input.loadObject();
            mouseStreamTimer = (Timer)input.loadObject();
            nextMouseStreamSend = input.loadLong();
            lastMouseButtons = input.loadInt();
        }
        if(!input.objectEndsHere()) {
            mouseHoldDx = input.loadInt();
            mouseHoldDy = input.loadInt();
            mouseHoldDz = input.loadInt();
        }
        sendKeyStatusReload();
    }

    public Keyboard()
    {
        ioportRegistered = false;
        modifierFlags = 0;
        keyStatus = new boolean[256];
        execKeyStatus = new boolean[256];
        queue = new KeyboardQueue(this);
        physicalAddressSpace = null;
        linearAddressSpace = null;
        cpu = null;
        mouseHoldDx = mouseHoldDy = mouseHoldDz = 0;
        reset();
        listeners = new ArrayList<KeyboardStatusListener>();
        sendKeyStatusReload();
    }

    //IOPortCapable Methods
    public int[] ioPortsRequested()
    {
        return new int[]{0x60, 0x64};
    }

    public int ioPortReadByte(int address)
    {
        switch (address) {
        case 0x60:
            return readData();
        case 0x64:
            return 0xff & status;
        default:
            return 0xffffffff;
        }
    }
    public int ioPortReadWord(int address)
    {
        return (0xff & ioPortReadByte(address)) | (0xff00 & ioPortReadByte(address + 1));
    }
    public int ioPortReadLong(int address)
    {
        return 0xffffffff;
    }

    public void ioPortWriteByte(int address, int data)
    {
        switch (address) {
        case 0x60:
            writeData((byte)data);
            break;
        case 0x64:
            writeCommand((byte)data);
            break;
        default:
        }
    }
    public void ioPortWriteWord(int address, int data)
    {
        ioPortWriteByte(address, data);
        ioPortWriteByte(address + 1, data >> 8);
    }

    public void ioPortWriteLong(int address, int data)
    {
        ioPortWriteWord(address, data);
        ioPortWriteWord(address + 2, data >> 16);
    }

    public void reset()
    {
        irqDevice = null;
        cpu = null;
        physicalAddressSpace = null;
        linearAddressSpace = null;
        ioportRegistered = false;


        keyboardWriteCommand = -1;
        mouseWriteCommand = -1;
        mode = KBD_MODE_KBD_INT | KBD_MODE_MOUSE_INT;
        status = (byte)(KBD_STAT_CMD | KBD_STAT_UNLOCKED);
        queue.reset();

        commandWrite = 0;
        keyboardWriteCommand = 0;
        keyboardScanEnabled = false;
        mouseWriteCommand = 0;
        mouseStatus = 0;
        mouseResolution = 0;
        mouseSampleRate = 0;
        mouseWrap = false;
        mouseDetectState = 0;
        mouseDx = 0;
        mouseDy = 0;
        mouseDz = 0;
        mouseButtons = 0;

        clock = null;
        mouseStreamTimer = null;
        nextMouseStreamSend = -1;
        lastMouseButtons = mouseButtons;
    }

    public void mouseSetHolds(int dx, int dy, int dz)
    {
        mouseHoldDx = dx;
        mouseHoldDy = dy;
        mouseHoldDz = dz;
    }

    private void setGateA20State(boolean value)
    {
        physicalAddressSpace.setGateA20State(value);
    }

    private byte readData()
    {
        byte val = queue.readData();
        updateIRQ();
        return val;
    }

    private void writeData(byte data)
    {
        switch(commandWrite) {
        case 0:
            writeKeyboard(data);
            break;
        case KBD_CCMD_WRITE_MODE:
            mode = 0xff & data;
            updateIRQ();
            break;
        case KBD_CCMD_WRITE_OBUF:
            queue.writeData(data, (byte)0);
            break;
        case KBD_CCMD_WRITE_AUX_OBUF:
            queue.writeData(data, (byte)1);
            break;
        case KBD_CCMD_WRITE_OUTPORT:
            setGateA20State((data & 0x2) != 0);
            if (0x1 != (data & 0x1))
                cpu.reset();
            break;
        case KBD_CCMD_WRITE_MOUSE:
            writeMouse(data);
            break;
        default:
            break;
        }
        commandWrite = (byte)0x00;
    }

    private void writeCommand(byte data)
    {
        switch(data) {
        case KBD_CCMD_READ_MODE:
            queue.writeData((byte)mode, (byte)0);
            break;
        case KBD_CCMD_WRITE_MODE:
        case KBD_CCMD_WRITE_OBUF:
        case KBD_CCMD_WRITE_AUX_OBUF:
        case KBD_CCMD_WRITE_MOUSE:
        case KBD_CCMD_WRITE_OUTPORT:
            commandWrite = data;
            break;
        case KBD_CCMD_MOUSE_DISABLE:
            mode |= KBD_MODE_DISABLE_MOUSE;
            break;
        case KBD_CCMD_MOUSE_ENABLE:
            mode &= ~KBD_MODE_DISABLE_MOUSE;
            break;
        case KBD_CCMD_TEST_MOUSE:
            queue.writeData((byte)0x00, (byte)0);
            break;
        case KBD_CCMD_SELF_TEST:
            status = (byte)(status | KBD_STAT_SELFTEST);
            queue.writeData((byte)0x55, (byte)0);
            break;
        case KBD_CCMD_KBD_TEST:
            queue.writeData((byte)0x00, (byte)0);
            break;
        case KBD_CCMD_KBD_DISABLE:
            mode |= KBD_MODE_DISABLE_KBD;
            updateIRQ();
            break;
        case KBD_CCMD_KBD_ENABLE:
            mode &= ~KBD_MODE_DISABLE_KBD;
            updateIRQ();
            break;
        case KBD_CCMD_READ_INPORT:
            queue.writeData((byte)0x00, (byte)0);
            break;
        case KBD_CCMD_READ_OUTPORT:
            /* XXX: check that */
            data = (byte)(0x01 | (physicalAddressSpace.getGateA20State() ? 0x02 : 0x00));
            if (0 != (status & KBD_STAT_OBF))
                data |= 0x10;
            if (0 != (status & KBD_STAT_MOUSE_OBF))
                data |= 0x20;
            queue.writeData(data, (byte)0);
            break;
        case KBD_CCMD_ENABLE_A20:
            setGateA20State(true);
            break;
        case KBD_CCMD_DISABLE_A20:
            setGateA20State(false);
            break;
        case KBD_CCMD_RESET:
            cpu.reset();
            break;
        case (byte)0xff:
            /* ignore that - I don't know what is its use */
            break;
        default:
            System.err.println("Warning: unsupported keyboard command " + Integer.toHexString(0xff & data) + ".");
            break;
        }
    }

    private void writeKeyboard(byte data)
    {
        switch(keyboardWriteCommand) {
        default:
        case -1:
            switch(data) {
            case 0x00:
                queue.writeData(KBD_REPLY_ACK, (byte)0);
                break;
            case 0x05:
                queue.writeData(KBD_REPLY_RESEND, (byte)0);
                break;
            case KBD_CMD_GET_ID:
                synchronized (queue) {
                    queue.writeData(KBD_REPLY_ACK, (byte) 0);
                    queue.writeData((byte) 0xab, (byte) 0);
                    queue.writeData((byte) 0x83, (byte) 0);
                }
                break;
            case KBD_CMD_ECHO:
                queue.writeData(KBD_CMD_ECHO, (byte)0);
                break;
            case KBD_CMD_ENABLE:
                keyboardScanEnabled = true;
                queue.writeData(KBD_REPLY_ACK, (byte)0);
                break;
            case KBD_CMD_SET_LEDS:
            case KBD_CMD_SET_RATE:
                keyboardWriteCommand = data;
                queue.writeData(KBD_REPLY_ACK, (byte)0);
                break;
            case KBD_CMD_RESET_DISABLE:
                resetKeyboard();
                keyboardScanEnabled = false;
                queue.writeData(KBD_REPLY_ACK, (byte)0);
                break;
            case KBD_CMD_RESET_ENABLE:
                resetKeyboard();
                keyboardScanEnabled = true;
                queue.writeData(KBD_REPLY_ACK, (byte)0);
                break;
            case KBD_CMD_RESET:
                resetKeyboard();
                synchronized (queue) {
                    queue.writeData(KBD_REPLY_ACK, (byte) 0);
                    queue.writeData(KBD_REPLY_POR, (byte) 0);
                }
                break;
            default:
                queue.writeData(KBD_REPLY_ACK, (byte)0);
                break;
            }
            break;
        case KBD_CMD_SET_LEDS:
            queue.writeData(KBD_REPLY_ACK, (byte)0);
            ledStatus = (int)data & 0xFF;
            sendLEDStatusChange(ledStatus);
            keyboardWriteCommand = -1;
            break;
        case KBD_CMD_SET_RATE:
            queue.writeData(KBD_REPLY_ACK, (byte)0);
            keyboardWriteCommand = -1;
            break;
        }
    }


    private void writeMouse(byte data)
    {
        switch(mouseWriteCommand) {
        default:
        case -1:
            /* mouse command */
            if (mouseWrap) {
                if (data == AUX_RESET_WRAP) {
                    mouseWrap = false;
                    queue.writeData(AUX_ACK, (byte)1);
                    return;
                } else if (data != AUX_RESET) {
                    queue.writeData(data, (byte)1);
                    return;
                }
            }
            switch(data) {
            case AUX_SET_SCALE11:
                mouseStatus &= ~MOUSE_STATUS_SCALE21;
                queue.writeData(AUX_ACK, (byte)1);
                break;
            case AUX_SET_SCALE21:
                mouseStatus |= MOUSE_STATUS_SCALE21;
                queue.writeData(AUX_ACK, (byte)1);
                break;
            case AUX_SET_STREAM:
                mouseStatus &= ~MOUSE_STATUS_REMOTE;
                queue.writeData(AUX_ACK, (byte)1);
                break;
            case AUX_SET_WRAP:
                mouseWrap = true;
                queue.writeData(AUX_ACK, (byte)1);
                break;
            case AUX_SET_REMOTE:
                mouseStatus |= MOUSE_STATUS_REMOTE;
                queue.writeData(AUX_ACK, (byte)1);
                break;
            case AUX_GET_TYPE:
                synchronized (queue) {
                    queue.writeData(AUX_ACK, (byte) 1);
                    queue.writeData((byte) MOUSE_TYPE, (byte) 1);
                }
                break;
            case AUX_SET_RES:
            case AUX_SET_SAMPLE:
                mouseWriteCommand = data;
                queue.writeData(AUX_ACK, (byte)1);
                break;
            case AUX_GET_SCALE:
                synchronized (queue) {
                    queue.writeData(AUX_ACK, (byte) 1);
                    queue.writeData((byte) mouseStatus, (byte) 1);
                    queue.writeData((byte) mouseResolution, (byte) 1);
                    queue.writeData((byte) mouseSampleRate, (byte) 1);
                }
                break;
            case AUX_POLL:
                synchronized (queue) {
                    queue.writeData(AUX_ACK, (byte) 1);
                    mouseSendPacket(true);
                    if(mouseDx == 0 && mouseDy == 0 && mouseDz == 0)
                        mouseSentPackets();
                }
                break;
            case AUX_ENABLE_DEV:
                mouseStatus |= MOUSE_STATUS_ENABLED;
                queue.writeData(AUX_ACK, (byte)1);
                break;
            case AUX_DISABLE_DEV:
                mouseStatus &= ~MOUSE_STATUS_ENABLED;
                queue.writeData(AUX_ACK, (byte)1);
                break;
            case AUX_SET_DEFAULT:
                mouseSampleRate = 100;
                mouseResolution = 2;
                mouseStatus = 0;
                queue.writeData(AUX_ACK, (byte)1);
                break;
            case AUX_RESET:
                mouseSampleRate = 100;
                mouseResolution = 2;
                mouseStatus = 0;
                synchronized (queue) {
                    queue.writeData(AUX_ACK, (byte) 1);
                    queue.writeData((byte) 0xaa, (byte) 1);
                    queue.writeData((byte) MOUSE_TYPE, (byte) 1);
                }
                break;
            default:
                break;
            }
            break;
        case AUX_SET_SAMPLE:
            mouseSampleRate = (int)data & 0xFF;
            queue.writeData(AUX_ACK, (byte)1);
            mouseWriteCommand = -1;
            break;
        case AUX_SET_RES:
            mouseResolution = data;
            queue.writeData(AUX_ACK, (byte)1);
            mouseWriteCommand = -1;
            break;
        }
    }

    private void resetKeyboard()
    {
        keyboardScanEnabled = true;
    }

    private int scale2(int value)
    {
        switch(value) {
        case -5: return -9;
        case -4: return -6;
        case -3: return -3;
        case -2: return -1;
        case -1: return -1;
        case 0: return 0;
        case 1: return 1;
        case 2: return 1;
        case 3: return 3;
        case 4: return 6;
        case 5: return 9;
        default: return 2 * value;
        }
    }

    private String S(int v)
    {
        return (new Integer(v)).toString();
    }

    private void mouseSentPackets()
    {
        if(recorder.getReadonlyMode()) {
            return;  //Avoid beeping in readonly mode.
        }
        //We can't use ts 0, because the event can then be sent immediately, which can cause desynchs.
        try {
            long ts = clock.getTime() + 1;
            if(mouseHoldDx != 0) recorder.addEvent(ts, getClass(), new String[]{"XMOUSEMOTION", S(mouseHoldDx)});
            if(mouseHoldDy != 0) recorder.addEvent(ts, getClass(), new String[]{"YMOUSEMOTION", S(mouseHoldDy)});
            if(mouseHoldDz != 0) recorder.addEvent(ts, getClass(), new String[]{"ZMOUSEMOTION", S(mouseHoldDz)});
        } catch(Exception e) {
            System.err.println("Failed to send mouse motion: " + e.getMessage());
        }
    }

    private void mouseSendPacket(boolean polled)
    {
        System.err.println("Called mouseSendPacket: dx=" + mouseDx + " dy=" + mouseDy + " dz=" + mouseDz + " buttons=" + mouseButtons + ".");
        int dx1 = mouseDx;
        int dy1 = mouseDy;
        int dz1 = mouseDz;

        if(!polled && (mouseStatus & MOUSE_STATUS_SCALE21) != 0) {
            dx1 = scale2(dx1);
            dy1 = scale2(dy1);
        }

        /* XXX: increase range to 8 bits ? */
        if (dx1 > 255) {
            dx1 = 255;
            System.err.println("Warning: Mouse X motion too fast!");
        } else if (dx1 < -255) {
            dx1 = -255;
            System.err.println("Warning: Mouse X motion too fast!");
        }
        if (dy1 > 255) {
            dy1 = 255;
            System.err.println("Warning: Mouse Y motion too fast!");
        } else if (dy1 < -255) {
            dy1 = -255;
            System.err.println("Warning: Mouse Y motion too fast!");
        }
        if (dz1 > 7) {
            dz1 = 7;
            System.err.println("Warning: Mouse Z motion too fast!");
        } else if (dz1 < -7) {
            dz1 = -7;
            System.err.println("Warning: Mouse Z motion too fast!");
        }
        int x = 0;
        int y = 0;
        if (dx1 < 0)
            x = 1;
        if (dy1 < 0)
            y = 1;
        byte b = (byte)(0x08 | (x << 4) | (y << 5) | (mouseButtons & 0x07));

        synchronized (queue) {
            queue.writeData(b, (byte) 1);
            queue.writeData((byte) dx1, (byte) 1);
            queue.writeData((byte) dy1, (byte) 1);
            /* extra byte for IMPS/2 or IMEX */
            switch (MOUSE_TYPE) {
                default:
                    break;
                case 3:
                    queue.writeData((byte) dz1, (byte) 1);
                    break;
                case 4:
                    b = (byte) ((dz1 & 0x0f) | ((mouseButtons & 0x18) << 1));
                    queue.writeData(b, (byte) 1);
                    break;
            }
        }

        /* update deltas */
        mouseDx -= dx1;
        mouseDy -= dy1;
        mouseDz -= dz1;
    }

    private void updateIRQ()
    {
        int irq1Level = 0;
        int irq12Level = 0;
        status = (byte)(status & ~(KBD_STAT_OBF | KBD_STAT_MOUSE_OBF));
        synchronized (queue) {
            if (queue.length != 0) {
                status = (byte) (status | KBD_STAT_OBF);
                if (0 != queue.getAux()) {
                    status = (byte) (status | KBD_STAT_MOUSE_OBF);
                    if (0 != (mode & KBD_MODE_MOUSE_INT))
                        irq12Level = 1;
                } else
                    if ((0 != (mode & KBD_MODE_KBD_INT)) &&
                            (0 == (mode & KBD_MODE_DISABLE_KBD)))
                        irq1Level = 1;
            }
        }
               irqDevice.setIRQ(1, irq1Level);
        irqDevice.setIRQ(12, irq12Level);
    }

    public static class KeyboardQueue implements SRDumpable
    {
        private byte[] aux;
        private byte[] data;
        private int readPosition;
        private int writePosition;
        private int length;
        private Keyboard upperBackref;

        public void dumpSRPartial(SRDumper output) throws IOException
        {
            output.dumpArray(aux);
            output.dumpArray(data);
            output.dumpInt(readPosition);
            output.dumpInt(writePosition);
            output.dumpInt(length);
            output.dumpObject(upperBackref);
        }

        public KeyboardQueue(SRLoader input) throws IOException
        {
            input.objectCreated(this);
            aux = input.loadArrayByte();
            data = input.loadArrayByte();
            readPosition = input.loadInt();
            writePosition = input.loadInt();
            length = input.loadInt();
            upperBackref = (Keyboard)input.loadObject();
        }

        public void dumpStatusPartial(StatusDumper output)
        {
            output.println("\tupperBackref <object #" + output.objectNumber(upperBackref) + ">"); if(upperBackref != null) upperBackref.dumpStatus(output);
            output.println("\treadPosition " + readPosition + " writePosition " + writePosition + " length " + length);
            for (int i = 0; i < aux.length; i++) {
                output.println("\taux[" + i + "] " + aux[i]);
            }
            for (int i = 0; i < data.length; i++) {
                output.println("\tdata[" + i + "] " + data[i]);
            }
        }

        public void dumpStatus(StatusDumper output)
        {
            if(output.dumped(this))
                return;

            output.println("#" + output.objectNumber(this) + ": KeyboardQueue:");
            dumpStatusPartial(output);
            output.endObject();
        }

        public KeyboardQueue(Keyboard backref)
        {
            aux = new byte[KBD_QUEUE_SIZE];
            data = new byte[KBD_QUEUE_SIZE];
            readPosition = 0;
            writePosition = 0;
            length = 0;
            upperBackref = backref;
        }

        public void reset()
        {
            synchronized (this) {
                readPosition = 0;
                writePosition = 0;
                length = 0;
            }
        }

        public byte getAux()
        {
            synchronized (this) {
                return aux[readPosition];
            }
        }

        public byte readData()
        {
            synchronized (this) {
                if (length == 0) {
                    /* NOTE: if no data left, we return the last keyboard one (needed for EMM386) */
                    /* XXX: need a timer to do things correctly */
                    int index = readPosition - 1;
                    if (index < 0)
                        index = KBD_QUEUE_SIZE - 1;
                    return data[index];
                }
                byte auxValue = this.aux[readPosition];
                byte dataValue = this.data[readPosition];
                if ((++readPosition) == KBD_QUEUE_SIZE)
                    readPosition = 0;
                length--;
                /* reading deasserts IRQ */
                if (0 != auxValue)
                    upperBackref.irqDevice.setIRQ(12, 0);
                else
                    upperBackref.irqDevice.setIRQ(1, 0);
                return dataValue;
            }
        }

        public void writeData(byte data, byte aux)
        {
            synchronized (this) {
                if (length >= KBD_QUEUE_SIZE)
                    return;
                this.aux[writePosition] = aux;
                this.data[writePosition] = data;
                if ((++writePosition) == KBD_QUEUE_SIZE)
                    writePosition = 0;
                length++;
            }
                   upperBackref.updateIRQ();
        }
    }


    public boolean getKeyStatus(byte scancode)
    {
        return keyStatus[(int)scancode & 0xFF];
    }

    public boolean getKeyExecStatus(byte scancode)
    {
        return execKeyStatus[(int)scancode & 0xFF];
    }

    public void keyPressed(byte scancode)
    {
        if(scancode != (byte)255) {
            keyStatus[(int)scancode & 0xFF] = true;
            sendKeyStatusChange((int)scancode & 0xFF, true);
        }
        if((scancode & 0x7F) == 29)
            modifierFlags |= 1;   //CTRL.
        if((scancode & 0x7F) == 56)
            modifierFlags |= 2;   //ALT.
        if((modifierFlags & 1) != 0 && scancode == (byte)255) {
            scancode = (byte)198;  //CTRL+BREAK
        }
        if((modifierFlags & 2) != 0 && scancode == (byte)183) {
            scancode = (byte)84;  //ALT+SYSRQ
            modifierFlags |= 4;   //SYSRQ.
        }

        synchronized(queue) {
            switch (scancode)
            {
            case (byte)0xff:
                putKeyboardEvent((byte)0xe1);
                putKeyboardEvent((byte)0x1d);
                putKeyboardEvent((byte)0x45);
                putKeyboardEvent((byte)0xe1);
                putKeyboardEvent((byte)0x9d);
                putKeyboardEvent((byte)0xc5);
                return;
            case (byte)198:
                //BREAK is special by being part of PAUSE.
                putKeyboardEvent((byte)0xe0);
                putKeyboardEvent((byte)0x46);
                putKeyboardEvent((byte)0xe0);
                putKeyboardEvent((byte)0xC6);
            default:
                if (scancode < 0)
                    putKeyboardEvent((byte)0xe0);
                putKeyboardEvent((byte)(scancode & 0x7f));
                return;
            }
        }
    }
    public void keyReleased(byte scancode)
    {
        if(scancode == (byte)255)
            return;                //PAUSE is autorelase key.
        keyStatus[(int)scancode & 0xFF] = false;
        sendKeyStatusChange((int)scancode & 0xFF, false);

        if((scancode & 0x7F) == 29)
            modifierFlags &= ~1;   //CTRL.
        if((scancode & 0x7F) == 56)
            modifierFlags &= ~2;   //ALT.
        if((modifierFlags & 4) != 0 && scancode == (byte)183) {
            scancode = (byte)84;   //ALT+SYSRQ
            modifierFlags &= ~4;   //SYSRQ.
        }
        synchronized(queue) {
            if (scancode < 0)
                putKeyboardEvent((byte)0xe0);
            putKeyboardEvent((byte)(scancode | 0x80));
        }
    }

    private void putKeyboardEvent(byte keycode)
    {
        queue.writeData(keycode, (byte)0);
    }

    public void putMouseEvent(int dx, int dy, int dz, int buttons)
    {
        if (0 == (mouseStatus & MOUSE_STATUS_ENABLED))
            return;

        mouseDx += dx;
        mouseDy -= dy;
        mouseDz += dz;

        mouseButtons = buttons;

        synchronized (queue) {
            if ((0 == (mouseStatus & MOUSE_STATUS_REMOTE)) && (queue.length < (KBD_QUEUE_SIZE - 16)))
                while (true) {
                    /* if not remote, send event.  Multiple events are sent if too big deltas */
                    mouseSendPacket(false);
                    if (mouseDx == 0 && mouseDy == 0 && mouseDz == 0) {
                        mouseSentPackets();
                        break;
                    }
                }
        }
    }

    public boolean initialised()
    {
        return ioportRegistered && (irqDevice != null) && (cpu != null) && (physicalAddressSpace != null) && (linearAddressSpace != null) && (clock != null);
    }

    public void acceptComponent(HardwareComponent component)
    {
        if((component instanceof InterruptController) && component.initialised())
            irqDevice = (InterruptController)component;

        if((component instanceof IOPortHandler) && component.initialised()) {
            ((IOPortHandler)component).registerIOPortCapable(this);
            ioportRegistered = true;
        }

        if((component instanceof Processor) && component.initialised())
            cpu = (Processor)component;

        if(component instanceof PhysicalAddressSpace)
            physicalAddressSpace = (PhysicalAddressSpace)component;

        if(component instanceof LinearAddressSpace)
            linearAddressSpace = (LinearAddressSpace)component;

        if((component instanceof Clock) && component.initialised()) {
            clock = (Clock)component;
            mouseStreamTimer = clock.newTimer(this);
            nextMouseStreamSend = clock.getTime();
            mouseStreamTimer.setExpiry(nextMouseStreamSend); //NOW!
            lastMouseButtons = 0;
        }
    }

    public void callback()
    {
        boolean doMouse = ((mouseStatus & (MOUSE_STATUS_ENABLED | MOUSE_STATUS_REMOTE)) == MOUSE_STATUS_ENABLED);
        boolean mouseChange = (mouseDx != 0 || mouseDy != 0 || mouseDz != 0 || lastMouseButtons != mouseButtons);
        if(clock.getTime() >= nextMouseStreamSend) {
            if(doMouse && mouseChange && queue.length < (KBD_QUEUE_SIZE - 8)) {
                mouseSendPacket(false);
                mouseDx = 0;
                mouseDy = 0;
                mouseDz = 0;
                mouseSentPackets();
                lastMouseButtons = mouseButtons;
            } else if(doMouse)
                mouseSentPackets();
            int sampleRate = mouseSampleRate;
            if(sampleRate < 10) sampleRate = 10;
            if(sampleRate > 200) sampleRate = 200;
            nextMouseStreamSend = nextMouseStreamSend + (1000000000L / sampleRate);
        }
        mouseStreamTimer.setExpiry(nextMouseStreamSend);
    }

    public int getTimerType()
    {
        return 67;
    }


    public void setEventRecorder(EventRecorder eRecorder)
    {
        recorder = eRecorder;
    }

    public void endEventCheck() throws IOException
    {
        suppressListened = false;
        sendKeyStatusReload();
    }

    public void startEventCheck()
    {
        suppressListened = true;
        modifierFlags2 = 0;
        keyboardTimeBound = 0;
        for(int i = 0; i < keyStatus.length; i++)
            keyStatus[i] = false;
        for(int i = 0; i < execKeyStatus.length; i++)
            execKeyStatus[i] = false;
        mouseButtonStatus = 0;
    }

    public void doEvent(long timeStamp, String[] args, int level) throws IOException
    {
        if(args == null || args.length == 0)
            throw new IOException("Empty events not allowed");
        if("PAUSE".equals(args[0])) {
            if(timeStamp < keyboardTimeBound && level <= EventRecorder.EVENT_STATE_EFFECT)
                throw new IOException("Invalid PAUSE event");
            if(args.length != 1 || timeStamp % CLOCKING_MODULO != 0)
                throw new IOException("Invalid PAUSE event");
            if(level >= EventRecorder.EVENT_EXECUTE)
                keyPressed((byte)255);
            else if((modifierFlags2 & 1) == 0)
                keyboardTimeBound = timeStamp + 60 * CLOCKING_MODULO;
            else
                keyboardTimeBound = timeStamp + 40 * CLOCKING_MODULO;  //Break takes 40 cycles, not 60 like pause.
        } else if("KEYEDGE".equals(args[0])) {
            if(timeStamp < keyboardTimeBound && level <= EventRecorder.EVENT_STATE_EFFECT)
                throw new IOException("Invalid KEYEDGE event");
            if(args.length != 2 || timeStamp % CLOCKING_MODULO != 0)
            {
                long was_time = timeStamp;
                long remainder = timeStamp % CLOCKING_MODULO;
                long better0 = was_time - remainder;
                long better1 = better0 + CLOCKING_MODULO;
                throw new IOException("Invalid KEYEDGE event at " + was_time + " (remainder = " + remainder + "); try " + better0 + "  or " + better1 + ")");
            }
            int scancode;
            try {
                scancode = Integer.parseInt(args[1]);
                if(scancode < 1 || (scancode > 95 && scancode < 129) || scancode > 223)
                    throw new IOException("Invalid key number");
                if(scancode == 84 || scancode == 198)
                    throw new IOException("Invalid key number");
            } catch(Exception e) {
                throw new IOException("Invalid KEYEDGE event");
            }

            if(level == EventRecorder.EVENT_STATE_EFFECT || level == EventRecorder.EVENT_STATE_EFFECT_FUTURE) {
                keyStatus[scancode] = !keyStatus[scancode];
                sendKeyStatusChange(scancode, keyStatus[scancode]);
            }
            if(level >= EventRecorder.EVENT_STATE_EFFECT) {
                execKeyStatus[scancode] = !execKeyStatus[scancode];
                sendKeyExecStatusChange(scancode, execKeyStatus[scancode]);
            }
            if(level >= EventRecorder.EVENT_EXECUTE)
                if(execKeyStatus[scancode]) {
                    keyPressed((byte)scancode);
                    System.err.println("Executing keyPress on " + scancode + ".");
                } else {
                    keyReleased((byte)scancode);
                    System.err.println("Executing keyRelease on " + scancode + ".");
                }
            else {
                if((modifierFlags2 & 6) != 0 && scancode == 183)
                    keyboardTimeBound = timeStamp + 10 * CLOCKING_MODULO; //SysRq takes 10 cycles per edge.
                else if(scancode < 128)
                    keyboardTimeBound = timeStamp + 10 * CLOCKING_MODULO;
                else
                    keyboardTimeBound = timeStamp + 20 * CLOCKING_MODULO;
            }
            //Upldate modifierFlags2.
            if((scancode & 0x7F) == 29)
                modifierFlags2 ^= 1;   //CTRL.
            if((scancode & 0x7F) == 56)
                modifierFlags2 ^= 2;   //ALT.
            if((modifierFlags2 & 6) != 0 && scancode == 183)
                modifierFlags2 ^= 4;   //SYSRQ.

        } else if("MOUSEBUTTON".equals(args[0])) {
            int scancode;
            try {
                scancode = Integer.parseInt(args[1]);
                if(scancode < 0 || scancode > 4)
                    throw new IOException("Invalid mouse button number");
            } catch(Exception e) {
                throw new IOException("Invalid MOUSEBUTTON event");
            }

            if(level == EventRecorder.EVENT_STATE_EFFECT || level == EventRecorder.EVENT_STATE_EFFECT_FUTURE ||
                level == EventRecorder.EVENT_TIMED) {
                mouseButtonStatus = mouseButtonStatus ^ (1 << scancode);
                sendMouseButtonsChange(mouseButtonStatus);
            }
            if(level == EventRecorder.EVENT_EXECUTE) {
                mouseButtons = mouseButtons ^ (1 << scancode);
                sendMouseExecButtonsChange(mouseButtons);
            }
        } else if("XMOUSEMOTION".equals(args[0])) {
            int amount;
            try {
                amount = Integer.parseInt(args[1]);
                if(amount < -255 || amount > 255)
                    throw new IOException("Invalid mouse X motion");
            } catch(Exception e) {
                throw new IOException("Invalid XMOUSEMOTION event");
            }

            if(level == EventRecorder.EVENT_EXECUTE)
                mouseDx += amount;
        } else if("YMOUSEMOTION".equals(args[0])) {
            int amount;
            try {
                amount = Integer.parseInt(args[1]);
                if(amount < -255 || amount > 255)
                    throw new IOException("Invalid mouse Y motion");
            } catch(Exception e) {
                throw new IOException("Invalid YMOUSEMOTION event");
            }

            if(level == EventRecorder.EVENT_EXECUTE)
                mouseDy += amount;
        } else if("ZMOUSEMOTION".equals(args[0])) {
            int amount;
            try {
                amount = Integer.parseInt(args[1]);
                if(amount < -7 || amount > 7)
                    throw new IOException("Invalid mouse Z motion");
            } catch(Exception e) {
                throw new IOException("Invalid ZMOUSEMOTION event");
            }

            if(level == EventRecorder.EVENT_EXECUTE)
                mouseDz += amount;
        } else
            throw new IOException("Invalid keyboard event subtype");
    }

    public long getEventTimeLowBound(long stamp, String[] args) throws IOException
    {
        //Mouse events are not bound by timing.
        if(args != null && args.length > 0 && ("MOUSEBUTTON".equals(args[0]) ||
            "XMOUSEMOTION".equals(args[0]) || "YMOUSEMOTION".equals(args[0]) ||
            "ZMOUSEMOTION".equals(args[0])))
            return stamp;

        if(keyboardTimeBound >= stamp)
            return keyboardTimeBound;
        else
            return stamp + (CLOCKING_MODULO - stamp % CLOCKING_MODULO) % CLOCKING_MODULO;
    }

    public void sendEdge(int scancode) throws IOException
    {
        String scanS = (new Integer(scancode)).toString();
        if(recorder == null)
            return;
        if(scancode < 0 || (scancode > 95 && scancode < 129) || (scancode > 223 && scancode != 255))
            throw new IOException("Invalid key number");
        if(scancode == 84 || scancode == 198)
            throw new IOException("Invalid key number");
        if(scancode == 255) {
            recorder.addEvent(keyboardTimeBound, getClass(), new String[]{"PAUSE"});
        } else if(scancode < 128) {
            recorder.addEvent(keyboardTimeBound, getClass(), new String[]{"KEYEDGE", scanS});
            keyStatus[scancode] = !keyStatus[scancode];
            sendKeyStatusChange(scancode, keyStatus[scancode]);
        } else {
            recorder.addEvent(keyboardTimeBound, getClass(), new String[]{"KEYEDGE", scanS});
            keyStatus[scancode] = !keyStatus[scancode];
            sendKeyStatusChange(scancode, keyStatus[scancode]);
        }
    }

    public int getLEDStatus()
    {
        return ledStatus;
    }

    private void sendKeyStatusChange(int scancode, boolean pressed)
    {
        if(suppressListened)
            return;
        for(KeyboardStatusListener x : listeners)
            x.keyStatusChange(scancode, pressed);
    }

    private void sendKeyExecStatusChange(int scancode, boolean pressed)
    {
        if(suppressListened)
            return;
        for(KeyboardStatusListener x : listeners)
            x.keyExecStatusChange(scancode, pressed);
    }

    private void sendKeyStatusReload()
    {
        if(suppressListened)
            return;
        for(KeyboardStatusListener x : listeners)
            x.keyStatusReload();
    }

    private void sendLEDStatusChange(int newstatus)
    {
        if(suppressListened)
            return;
        for(KeyboardStatusListener x : listeners)
            x.ledStatusChange(newstatus);
    }

    public void sendMouseButtonsChange(int newstatus)
    {
        if(suppressListened)
            return;
        for(KeyboardStatusListener x : listeners)
            x.mouseButtonsChange(newstatus);
    }

    public void sendMouseExecButtonsChange(int newstatus)
    {
        if(suppressListened)
            return;
        for(KeyboardStatusListener x : listeners)
            x.mouseExecButtonsChange(newstatus);
    }

    public void addStatusListener(KeyboardStatusListener l)
    {
        listeners.add(l);
    }

    public void removeStatusListener(KeyboardStatusListener l)
    {
        listeners.remove(l);
    }

    public void sendMouseButton(int button) throws IOException
    {
        String scanS = (new Integer(button)).toString();
        if(recorder == null)
            return;
        if(button < 0 || button > 4)
            throw new IOException("Invalid mouse button number");
        recorder.addEvent(-1, getClass(), new String[]{"MOUSEBUTTON", scanS});
    }

    public void sendXMouseMotion(int amount) throws IOException
    {
        String scanS = (new Integer(amount)).toString();
        if(recorder == null)
            return;
        if(amount < -255 || amount > 255)
            throw new IOException("Invalid X mouse motion amount");
        recorder.addEvent(-1, getClass(), new String[]{"XMOUSEMOTION", scanS});
    }

    public void sendYMouseMotion(int amount) throws IOException
    {
        String scanS = (new Integer(amount)).toString();
        if(recorder == null)
            return;
        if(amount < -255 || amount > 255)
            throw new IOException("Invalid Y mouse motion amount");
        recorder.addEvent(-1, getClass(), new String[]{"YMOUSEMOTION", scanS});
    }

    public void sendZMouseMotion(int amount) throws IOException
    {
        String scanS = (new Integer(amount)).toString();
        if(recorder == null)
            return;
        if(amount < -7 || amount > 7)
            throw new IOException("Invalid Z mouse motion amount");
        recorder.addEvent(-1, getClass(), new String[]{"ZMOUSEMOTION", scanS});
    }

    public int getMouseButtonStatus()
    {
        return mouseButtons;
    }

    public int getMouseXPendingMotion()
    {
        return mouseDx;
    }

    public int getMouseYPendingMotion()
    {
        return mouseDy;
    }

    public int getMouseZPendingMotion()
    {
        return mouseDz;
    }
}
