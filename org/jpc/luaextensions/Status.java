/*
    JPC-RR: A x86 PC Hardware Emulator
    Release 1

    Copyright (C) 2007-2009 Isis Innovation Limited
    Copyright (C) 2009-2011 H. Ilari Liusvaara

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

import org.jpc.emulator.Clock;
import org.jpc.emulator.PC;
import org.jpc.emulator.pci.peripheral.VGACard;
import org.jpc.emulator.DisplayController;
import org.jpc.emulator.peripheral.Keyboard;
import org.jpc.modules.Joystick;
import org.jpc.emulator.EventRecorder;
import org.jpc.emulator.motherboard.IntervalTimer;
import org.jpc.plugins.LuaPlugin;

//Locking this class is used for preventing termination and when terminating.
public class Status extends LuaPlugin.LuaResource
{
    public void destroy()
    {
    }

    private Status(LuaPlugin plugin)
    {
        super(plugin);
    }

    public static int luaCB_status(Lua l, LuaPlugin plugin)
    {
        if(!plugin.getPCConnected())
            return 0;
        LuaTable tab = l.newTable();
        l.setTable(tab, "running", plugin.getPCRunning());
        l.push(tab);
        Clock clk = (Clock)plugin.getComponent(Clock.class);
        if(clk != null)
            l.setTable(tab, "time", new Double(clk.getTime()));
        l.setTable(tab, "resolution_x", new Double(plugin.getXResolution()));
        l.setTable(tab, "resolution_y", new Double(plugin.getYResolution()));
        DisplayController dc = ((DisplayController)plugin.getComponent(DisplayController.class));
        if(dc != null)
            l.setTable(tab, "frame", new Double(dc.getFrameNumber()));
        PC.ResetButton brb = ((PC.ResetButton)plugin.getComponent(PC.ResetButton.class));
        if(brb != null) {
            EventRecorder rec = brb.getRecorder();
            long lastTime = rec.getLastEventTime();
            long attachTime = rec.getAttachTime();
            long time = 0;
            if(attachTime < lastTime)
                time = lastTime - attachTime;
            l.setTable(tab, "movie_length", new Double(time));
            l.setTable(tab, "movie_rerecords", rec.getRerecordCount().doubleValue());
            String[][] headers = rec.getHeaders();
            LuaTable ret = l.newTable();
            if(headers != null)
                for(int i = 0; i < headers.length; i++) {
                    LuaTable tab2 = l.newTable();
                    int j = 1;
                    if(headers[i] != null)
                        for(String p : headers[i])
                            l.setTable(tab2, new Double(j++), p);
                    l.setTable(ret, new Double(i + 1), tab2);
                }
            l.setTable(tab, "movie_headers", ret);
            l.setTable(tab, "project_id", rec.getProjectID());
        }
        VGACard card = (VGACard)plugin.getComponent(VGACard.class);
        if(card != null) {
            l.setTable(tab, "vga_scrolls", new Double(card.getScrollCount()));
            l.setTable(tab, "vga_retraces_seen", new Double(card.getVretraceEdgeCount()));
        }
        IntervalTimer iclk = (IntervalTimer)plugin.getComponent(IntervalTimer.class);
        if(iclk != null) {
            l.setTable(tab, "irq0_dispatched", new Double(iclk.getIRQ0Cycles()));
            l.setTable(tab, "irq0_frequency", new Double(iclk.getIRQ0Rate()));
        }
        Keyboard key = (Keyboard)plugin.getComponent(Keyboard.class);
        if(key != null) {
            int status = key.getLEDStatus();
            if(status < 0) {
                l.setTable(tab, "keyboard_leds_valid", false);
            } else {
                l.setTable(tab, "keyboard_leds_valid", true);
                l.setTable(tab, "keyboard_num_lock", (status & 2) != 0);
                l.setTable(tab, "keyboard_caps_lock", (status & 4) != 0);
                l.setTable(tab, "keyboard_scroll_lock", (status & 1) != 0);
            }
            l.setTable(tab, "mouse_dx_pending", new Double(key.getMouseXPendingMotion()));
            l.setTable(tab, "mouse_dy_pending", new Double(key.getMouseYPendingMotion()));
            l.setTable(tab, "mouse_dz_pending", new Double(key.getMouseZPendingMotion()));
            int b = key.getMouseButtonStatus();
            l.setTable(tab, "mouse_button_1", (b & 1) != 0);
            l.setTable(tab, "mouse_button_2", (b & 2) != 0);
            l.setTable(tab, "mouse_button_3", (b & 4) != 0);
            l.setTable(tab, "mouse_button_4", (b & 8) != 0);
            l.setTable(tab, "mouse_button_5", (b & 16) != 0);
        }
        Joystick joy = (Joystick)plugin.getComponent(Joystick.class);
        if(joy != null) {
            l.setTable(tab, "joystick_ax", new Double(joy.axisHoldTime(0, false)));
            l.setTable(tab, "joystick_ay", new Double(joy.axisHoldTime(1, false)));
            l.setTable(tab, "joystick_bx", new Double(joy.axisHoldTime(2, false)));
            l.setTable(tab, "joystick_by", new Double(joy.axisHoldTime(3, false)));
            l.setTable(tab, "joystick_aa", joy.buttonState(0, false));
            l.setTable(tab, "joystick_ab", joy.buttonState(1, false));
            l.setTable(tab, "joystick_ba", joy.buttonState(2, false));
            l.setTable(tab, "joystick_bb", joy.buttonState(3, false));
        }
        return 1;
    }
}
