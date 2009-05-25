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

import org.jpc.emulator.*;
import org.jpc.emulator.memory.*;
import java.io.*;
import org.jpc.support.Magic;

public class VGABIOS extends AbstractHardwareComponent implements IOPortCapable
{
    private byte[] imageData;
    private boolean ioportRegistered, loaded;
    private Magic magic;

    public VGABIOS(byte[] image)
    {
        magic = new Magic(Magic.VGA_BIOS_MAGIC_V1);
        loaded = false;
        ioportRegistered = false;

        imageData = new byte[image.length];
        System.arraycopy(image, 0, imageData, 0, image.length);
    }

    public VGABIOS(String imagefile) throws IOException
    {
        magic = new Magic(Magic.VGA_BIOS_MAGIC_V1);
        InputStream in = null;
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            in = new FileInputStream(imagefile);

            while (true)
            {
                int ch = in.read();
                if (ch < 0)
                    break;
                bout.write((byte) ch);
            }

            imageData = bout.toByteArray();
        }
        finally
        {
            try
            {
                in.close();
            }
            catch (Exception e) {}
        }
    }

    public void dumpState(DataOutput output) throws IOException
    {
        magic.dumpState(output);
        output.writeInt(imageData.length);
        output.write(imageData);
    }

    public void dumpStatusPartial(org.jpc.support.StatusDumper output)
    {
        super.dumpStatusPartial(output);
        output.println("\tioportRegistered " + ioportRegistered + " loaded " + loaded);
        output.println("\timageData:");
        output.printArray(imageData, "imageData"); 
    }
 
    public void dumpStatus(org.jpc.support.StatusDumper output)
    {
        if(output.dumped(this))
            return;

        output.println("#" + output.objectNumber(this) + ": VGABIOS:");
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
        output.dumpBoolean(ioportRegistered);
        output.dumpArray(imageData);
    }

    public void loadState(DataInput input) throws IOException
    {
        magic.loadState(input);
        int len = input.readInt();
        imageData = new byte[len];
        input.readFully(imageData,0,len);
    }

    public int[] ioPortsRequested()
    {
        return new int[]{0x500, 0x501, 0x502, 0x503};
    }

    public int ioPortReadByte(int address) { return 0xff; }
    public int ioPortReadWord(int address) { return 0xffff; }
    public int ioPortReadLong(int address) { return (int)0xffffffff; }

    public void ioPortWriteByte(int address, int data)
    {
        switch(address)
        {
            /* LGPL VGA-BIOS Messages */
        case 0x500:
        case 0x503:
            try
            {
                System.out.print(new String(new byte[]{(byte)data},"US-ASCII"));
            }
            catch (Exception e)
            {
                System.out.print(new String(new byte[]{(byte)data}));
            }
            break;
        default:
        }
    }
    public void ioPortWriteWord(int address, int data)
    {
        switch(address) {
            /* Bochs BIOS Messages */
        case 0x501:
        case 0x502:
            System.err.println("VGA-BIOS panic line " + data);
        default:
        }
    }
    public void ioPortWriteLong(int address, int data) {}

    public void load(PhysicalAddressSpace physicalAddress)
    {
        int blockSize = AddressSpace.BLOCK_SIZE;
        int len = ((imageData.length-1) / blockSize + 1)*blockSize;

        for (int i=0; i<len/blockSize; i++)
        {
            EPROMMemory ep = new EPROMMemory(blockSize, 0, imageData, i*blockSize, blockSize);
            physicalAddress.allocateMemory(0xC0000 + i*blockSize, ep);
        }
    }

    public byte[] getImage()
    {
        return (byte[]) imageData.clone();
    }

    public boolean updated()
    {
        return (loaded && ioportRegistered);
    }

    public void updateComponent(HardwareComponent component)
    {
        if ((component instanceof PhysicalAddressSpace) && component.updated())
        {
            this.load((PhysicalAddressSpace)component);
            loaded = true;
        }

        if ((component instanceof IOPortHandler) && component.updated())
        {
            ((IOPortHandler)component).registerIOPortCapable(this);
            ioportRegistered = true;
        }
    }

    public boolean initialised()
    {
        return (loaded && ioportRegistered);
    }

    public void acceptComponent(HardwareComponent component)
    {
        if ((component instanceof PhysicalAddressSpace) && component.initialised())
        {
            this.load((PhysicalAddressSpace)component);
            loaded = true;
        }
        if ((component instanceof IOPortHandler) && component.initialised())
        {
            ((IOPortHandler)component).registerIOPortCapable(this);
            ioportRegistered = true;
        }
    }

    public void reset()
    {
        ioportRegistered = false;
        loaded = false;
    }
}
