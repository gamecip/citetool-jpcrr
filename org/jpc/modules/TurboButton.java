/*
    JPC-RR: A x86 PC Hardware Emulator
    Release 1

    Copyright (C) 2007-2009 Isis Innovation Limited
    Copyright (C) 2009-2014 H. Ilari Liusvaara

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
import org.jpc.emulator.processor.Processor;
import org.jpc.emulator.motherboard.*;
import java.io.*;

public class TurboButton extends AbstractHardwareComponent implements EventDispatchTarget
{
    private Processor cpu;
    private Clock clock;
    private int savedRate;

    //Not saved.
    public String STATUS_TURBO;
    private boolean edgeActive;
    private EventRecorder rec;

    public void dumpSRPartial(SRDumper output) throws IOException
    {
        super.dumpSRPartial(output);
        output.dumpObject(cpu);
        output.dumpObject(clock);
        output.dumpInt(savedRate);
    }

    public TurboButton(SRLoader input) throws IOException
    {
        super(input);
        cpu = (Processor)input.loadObject();
        clock = (Clock)input.loadObject();
        savedRate = input.loadInt();
    }

    public TurboButton() throws IOException
    {
        cpu = null;
        clock = null;
        savedRate = -1;
        STATUS_TURBO = "N/A";
    }

    public void dumpStatusPartial(StatusDumper output)
    {
        super.dumpStatusPartial(output);
        output.println("\tclock <object #" + output.objectNumber(clock) + ">"); if(clock != null) clock.dumpStatus(output);
        output.println("\tclock <object #" + output.objectNumber(cpu) + ">"); if(cpu != null) cpu.dumpStatus(output);
        output.println("\tsavedRate " + savedRate);
    }

    public void dumpStatus(StatusDumper output)
    {
        if(output.dumped(this))
            return;

        output.println("#" + output.objectNumber(this) + ": TurboButton:");
        dumpStatusPartial(output);
        output.endObject();
    }

    public boolean initialised()
    {
        return ((clock != null) && (cpu != null));
    }

    public void acceptComponent(HardwareComponent component)
    {
        if((component instanceof Clock) && component.initialised())
            clock = (Clock)component;
        if((component instanceof Processor) && component.initialised())
            cpu = (Processor)component;
    }

    public boolean turboState(boolean inputEdge)
    {
        if(inputEdge)
            return edgeActive;
        else
            return (savedRate > 0);
    }

    public void setTurbo(boolean state) throws IOException
    {
        if(state == edgeActive) return;
        rec.addEvent(0, getClass(), new String[]{"TOGGLE"});
        edgeActive = state;
    }

    public void reset()
    {
        //Nothing to do.
    }

    public void setEventRecorder(EventRecorder recorder)
    {
        rec = recorder;
    }

    public void startEventCheck()
    {
        if(savedRate > 0)
            cpu.clockDivider = savedRate;
        savedRate = -1;
        STATUS_TURBO = "" + cpu.clockDivider;
        edgeActive = false;
    }

    public void endEventCheck() throws IOException
    {
        STATUS_TURBO = "" + cpu.clockDivider;
    }

    public long getEventTimeLowBound(long stamp, String[] args) throws IOException
    {
        return -1;  //No constraints.
    }

    public void doEvent(long timeStamp, String[] args, int level) throws IOException
    {
        boolean show = (level == EventRecorder.EVENT_EXECUTE);
        if(args == null || args.length != 1) {
            throw new IOException("Turbo button events must have one element");
        }
        if(!"TOGGLE".equals(args[0]))
            throw new IOException("Bad joystick event type '" + args[0] + "'");

        if(level == EventRecorder.EVENT_STATE_EFFECT || level == EventRecorder.EVENT_EXECUTE) {
            if(savedRate > 0) {
                cpu.clockDivider = savedRate;
                savedRate = -1;
                if(show) System.out.println("Turbo disabled");
            } else {
                savedRate = cpu.clockDivider;
                cpu.clockDivider = 1;
                if(show) System.out.println("Turbo enabled");
            }
            STATUS_TURBO = "" + cpu.clockDivider;
        }
        if(level == EventRecorder.EVENT_STATE_EFFECT || level == EventRecorder.EVENT_STATE_EFFECT_FUTURE) {
            edgeActive = !edgeActive;
        }
    }
}
