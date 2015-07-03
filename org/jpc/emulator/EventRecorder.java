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

package org.jpc.emulator;

import org.jpc.jrsr.UTFInputLineStream;
import org.jpc.jrsr.UTFOutputLineStream;
import static org.jpc.Misc.nextParseLine;
import java.io.*;
import java.util.*;
import java.math.BigInteger;
import static org.jpc.Misc.errorDialog;
import static org.jpc.Misc.emitBeep;

public class EventRecorder implements TimerResponsive
{
     private static final int EVENT_MAGIC_CLASS = 0;
     private static final int EVENT_MAGIC_SAVESTATE = 1;
     private static final int EVENT_MAGIC_NONE = -1;

     public static final int EVENT_TIMED = 0;
     public static final int EVENT_STATE_EFFECT_FUTURE = 1;
     public static final int EVENT_STATE_EFFECT = 2;
     public static final int EVENT_EXECUTE = 3;

     public class ReturnEvent
     {
         public long timestamp;
         public String[] eventData;
     }

     public class Event
     {
         public long timestamp;                               //Event timestamp (low bound)
         public long sequenceNumber;                          //Sequence number.
         public int magic;                                    //Magic type.
         public Class<? extends HardwareComponent> clazz;     //Dispatch to where.
         public String[] args;                                //Arguments to dispatch.
         public Event prev;                                   //Previous event.
         public Event next;                                   //Next event.

         public void dispatch(PC target, int level) throws IOException
         {
             if(magic != EVENT_MAGIC_CLASS)
                 return;   //We really don't want to dispatch these.

             HardwareComponent hwc = target.getComponent(clazz);
             if(hwc == null)
                 throw new IOException("Invalid event target \"" + clazz.getName() + "\": no component of such type");
             EventDispatchTarget component = (EventDispatchTarget)hwc;
             component.doEvent(timestamp, args, level);
         }
     }

     private Event first;
     private Event current;
     private Event last;
     private Event cache;
     private Event firstUndispatched;
     private Event lastUndispatched;
     private PC pc;
     private boolean directMode;
     private Clock sysClock;
     private Timer sysTimer;
     private long timerInvokeTime;
     private BigInteger savestateRerecordCount;
     private boolean dirtyFlag;
     private long cleanTime;
     private long attachTime;
     private String[][] headers;
     private BigInteger movieRerecordCount;
     private String projectID;
     private boolean readonlyMode;

     public void setReadonlyMode(boolean newState)
     {
         boolean previous = readonlyMode;
         readonlyMode = newState;
         if(previous && !readonlyMode)
             truncateEventStream();
     }

     public boolean getReadonlyMode()
     {
         return readonlyMode;
     }

     public String getProjectID()
     {
         return projectID;
     }

     public void setProjectID(String proj)
     {
         projectID = proj;
     }

     private boolean isDirty()
     {
         //sysClock == null should not happen, but include it just for sure.
         return (dirtyFlag || sysClock == null || cleanTime != sysClock.getTime());
     }

     private void setClean()
     {
         dirtyFlag = false;
         cleanTime = (sysClock != null) ? sysClock.getTime() : -1;
     }

     public long getAttachTime()
     {
         return attachTime;
     }

     public BigInteger getRerecordCount()
     {
         return movieRerecordCount.subtract(savestateRerecordCount);
     }

     public void setRerecordCount(BigInteger newCount)
     {
         movieRerecordCount = newCount;
     }

     public String[][] getHeaders()
     {
         return headers;
     }

     public void setHeaders(String[][] newHeaders)
     {
         if(newHeaders == null) {
             headers = null;
             return;
         }

         String[][] tmp = new String[newHeaders.length][];
         for(int i = 0; i < tmp.length; i++)
             if(newHeaders[i] != null)
                 tmp[i] = Arrays.copyOf(newHeaders[i], newHeaders[i].length);

        headers = tmp;
     }

     public void setTimer(long time)
     {
         if(directMode) {
             sysTimer.disable();     //No need for timers in direct mode.
             return;
         }
         if((sysTimer.enabled() && timerInvokeTime <= time))
             return;         //No need for timer.
         sysTimer.setExpiry(timerInvokeTime = time);
     }

     public void addEvent(long timeLowBound, Class<? extends HardwareComponent> clazz,
         String[] args) throws IOException
     {
         if(readonlyMode) {
             emitBeep();
             return;
         }
         /* Compute the final time for event. */
         long timeNow = sysClock.getTime();
         synchronized(this) {
             long time = timeLowBound;
             if(last != null && time < last.timestamp)
                 time = last.timestamp;
             if(time < timeNow)
                 time = timeNow;

             HardwareComponent hwc = pc.getComponent(clazz);
             EventDispatchTarget component = (EventDispatchTarget)hwc;
             long freeLowBound = -1;
             try {
                 freeLowBound = component.getEventTimeLowBound(time, args);
             } catch(Exception e) {};  //Shouldn't throw.
             if(time < freeLowBound)
                 time = freeLowBound;

             Event ev = new Event();
             ev.timestamp = time;
             ev.magic = EVENT_MAGIC_CLASS;
             ev.clazz = clazz;
             ev.args = args;

             if(firstUndispatched == null)
                 firstUndispatched = ev;
             if(lastUndispatched != null)
                 lastUndispatched.next = ev;
             ev.prev = lastUndispatched;
             lastUndispatched = ev;
         }

         if(directMode) {
             handleUndispatchedEvents();
         } else {
             setTimer(timeNow);   //Fire it as soon as possible.
         }
     }


     private void handleUndispatchedEvents()
     {
         //This has toi be outside or we can have ABBA deadlock.
         long timeNow = sysClock.getTime();

         //Synchronize to prevent racing with addEvent()
         synchronized(this) {
             //First move undispatched events to main queue.
             Event scan = firstUndispatched;
             while(scan != null) {
                 dirtyFlag = true;
                 //Compute time for event.
                 Event scanNext = scan.next;
                 if(scan.timestamp < timeNow)
                     scan.timestamp = timeNow;
                 if(last != null && scan.timestamp < last.timestamp)
                     scan.timestamp = last.timestamp;

                 HardwareComponent hwc = pc.getComponent(scan.clazz);
                 EventDispatchTarget component = (EventDispatchTarget)hwc;
                 long freeLowBound = -1;
                 try {
                     freeLowBound = component.getEventTimeLowBound(scan.timestamp, scan.args);
                 } catch(Exception e) {};  //Shouldn't throw.
                 if(scan.timestamp < freeLowBound)
                     scan.timestamp = freeLowBound;

                 try {
                     scan.dispatch(pc, EVENT_TIMED);
                 } catch(Exception e) {
                     System.err.println("Error: Event dispatch failed.");
                     errorDialog(e, "Failed to dispatch event", null, "Dismiss");
                     scan = null;
                 }

                 //Because of constraints to time, the event must go last.
                 if(scan != null) {
                     //Sequence number for first event is 0 and then increments by 1 for each event.
                     scan.sequenceNumber = ((last != null) ? (last.sequenceNumber + 1) : 0);
                     scan.next = null;
                     scan.prev = last;
                     if(last != null)
                         last.next = scan;
                     last = scan;
                     if(current == null)
                         current = scan;
                     if(first == null)
                         first = scan;
                 }
                 scan = scanNext;
             }
             firstUndispatched = null;
             lastUndispatched = null;
         }

         //Then fire apporiate events from main queue.
         while(current != null && current.timestamp <= timeNow) {
             try {
                 current.dispatch(pc, EVENT_EXECUTE);
             } catch(Exception e) {
                 System.err.println("Error: Event dispatch failed.");
                 errorDialog(e, "Failed to dispatch event", null, "Dismiss");
             }
             current = current.next;
         }
         if(current != null)
             setTimer(current.timestamp);
     }

     public synchronized void setPCRunStatus(boolean running)
     {
         directMode = !running;
         if(directMode)
             handleUndispatchedEvents();
         if(current != null)
             setTimer(current.timestamp);
     }

     public void truncateEventStream()
     {
         if(current != null) {
             dirtyFlag = true;
             if(cache != null && current.sequenceNumber <= cache.sequenceNumber)
                 cache = null;   //Flush cache as event got truncated out.
             last = current.prev;
             current = null;
             if(last != null)
                 last.next = null;
             else
                 first = null;
             Event scan = first;
             dispatchStart(pc);
             while(scan != null) {
                 try {
                     scan.dispatch(pc, EVENT_STATE_EFFECT);
                 } catch(Exception e) {}
                 scan = scan.next;
             }
             try {
                 dispatchEnd(pc);
             } catch(Exception e) {}
         }
         firstUndispatched = null;
         lastUndispatched = null;
     }

     private void dispatchStart(PC target)
     {
         Set<HardwareComponent> pcParts = target.allComponents();
         for(HardwareComponent hwc : pcParts) {
             if(!EventDispatchTarget.class.isAssignableFrom(hwc.getClass()))
                 continue;
             EventDispatchTarget t = (EventDispatchTarget)hwc;
             t.setEventRecorder(this);
             t.startEventCheck();
         }
     }

     private void dispatchEnd(PC target) throws IOException
     {
         Set<HardwareComponent> pcParts = target.allComponents();
         for(HardwareComponent hwc : pcParts) {
             if(!EventDispatchTarget.class.isAssignableFrom(hwc.getClass()))
                 continue;
             EventDispatchTarget t = (EventDispatchTarget)hwc;
             t.endEventCheck();
         }
     }

     public EventRecorder()
     {
         first = null;
         current = null;
         last = null;
         cache = null;
         firstUndispatched = null;
         lastUndispatched = null;
         directMode = true;
         dirtyFlag = true;
         cleanTime = -1;
         savestateRerecordCount = new BigInteger("0");
         movieRerecordCount = new BigInteger("0");
     }

     private static boolean isReservedName(String name)
     {
         for(int i = 0; i < name.length(); i++) {
             char j = name.charAt(i);
             if(j >= '0' && j <= '9')
                 continue;
             if(j >= 'A' && j <= 'Z')
                 continue;
             return false;
         }
         return true;
     }

     public EventRecorder(UTFInputLineStream lines) throws IOException
     {
         boolean relativeTime = false;
         long lastTimestamp = 0;
         dirtyFlag = true;
         cleanTime = -1;
         String[] components = nextParseLine(lines);
         while(components != null) {
             Event ev = new Event();
             if(components.length < 2)
                 throw new IOException("Malformed event line");
             long timeStamp;
             try {
                 if(relativeTime)
                     ev.timestamp = timeStamp = lastTimestamp = Long.parseLong(components[0]) + lastTimestamp;
                 else
                     ev.timestamp = timeStamp = lastTimestamp = Long.parseLong(components[0]);
                 if(timeStamp < 0)
                     throw new IOException("Negative timestamp value " + timeStamp + " not allowed");
             } catch(NumberFormatException e) {
                 throw new IOException("Invalid timestamp value \"" + components[0] + "\"");
             }
             if(last != null && timeStamp < last.timestamp)
                 throw new IOException("Timestamp order violation: " + timeStamp + "<" + last.timestamp);
             String clazzName = components[1];
             if(clazzName.equals("SAVESTATE")) {
                 if(components.length < 3 || components.length > 4)
                     throw new IOException("Malformed SAVESTATE line");
                 ev.magic = EVENT_MAGIC_SAVESTATE;
                 ev.clazz = null;
                 if(components.length == 3)
                     ev.args = new String[]{components[2], "0"};
                 else
                     ev.args = new String[]{components[2], components[3]};
             } else if(clazzName.equals("OPTION")) {
                 if(components.length != 3)
                     throw new IOException("Malformed OPTION line");
                 if("RELATIVE".equals(components[2]))
                     relativeTime = true;
                 else if("ABSOLUTE".equals(components[2]))
                     relativeTime = false;
                 else
                     throw new IOException("Unknown OPTION: '" + components[2] + "'");
                 ev.magic = EVENT_MAGIC_NONE;
             } else if(isReservedName(clazzName)) {
                 throw new IOException("Unknown special event type: '" + clazzName + "'");
             } else {
                 //Something dispatchable.
                 ev.magic = EVENT_MAGIC_CLASS;
                 Class<?> clazz;
                 try {
                     clazz = Class.forName(clazzName);
                     if(!EventDispatchTarget.class.isAssignableFrom(clazz))
                         throw new Exception("bad class");
                     if(!HardwareComponent.class.isAssignableFrom(clazz))
                         throw new Exception("bad class");
                 } catch(Exception e) {
                     throw new IOException("\"" + clazzName + "\" is not valid event target");
                 }
                 ev.clazz = clazz.asSubclass(HardwareComponent.class);
                 if(components.length == 2)
                     ev.args = null;
                 else {
                     ev.args = new String[components.length - 2];
                     System.arraycopy(components, 2, ev.args, 0, ev.args.length);
                 }
             }

             if(ev.magic != EVENT_MAGIC_NONE) {
                 ev.prev = last;
                 //Sequence number for first event is 0 and then increments by 1 for each event.
                 ev.sequenceNumber = ((last != null) ? (last.sequenceNumber + 1) : 0);
                 if(last == null)
                     first = ev;
                 else
                     last.next = ev;
                 last = ev;
             }
             components = nextParseLine(lines);
         }

         firstUndispatched = null;
         lastUndispatched = null;
         directMode = true;
     }

     private void iterateIncrementSequence(Event from)
     {
         while(from != null) {
             from.sequenceNumber++;
             from = from.next;
         }
     }

     public void markSave(String id, BigInteger rerecords) throws IOException
     {
         if(!isDirty())
             rerecords = savestateRerecordCount;
         else
             savestateRerecordCount = rerecords;
         /* Current is next event to dispatch. So add it before it. Null means add to
            end. */
         Event ev = new Event();
         ev.timestamp = sysClock.getTime();
         ev.magic = EVENT_MAGIC_SAVESTATE;
         ev.clazz = null;
         ev.args = new String[]{id, rerecords.toString()};
         ev.next = current;
         if(current != null) {
            //Give it current's sequence number and increment the rest of sequence numbers.
            ev.sequenceNumber = current.sequenceNumber;
            iterateIncrementSequence(current);
            ev.prev = current.prev;
            if(ev.prev != null)
                ev.prev.next = ev;
            current.prev = ev;
            ev.next = current;
         } else {
            //Sequence number for first event is 0 and then increments by 1 for each event.
            ev.sequenceNumber = ((last != null) ? (last.sequenceNumber + 1) : 0);
            ev.prev = last;
            if(last != null)
                last.next = ev;
            last = ev;
         }
         if(ev.prev == null) {
            first = ev;
         }
         setClean();
     }

     public void attach(PC aPC, String id) throws IOException
     {
         Event oldCurrent = current;

         Clock newSysClock = (Clock)aPC.getComponent(Clock.class);
         long expectedTime = newSysClock.getTime();
         BigInteger rerecordCount = new BigInteger("0");
         if(id == null) {
             current = first;
         } else {
             Event scan = first;
             while(scan != null) {
                 if(scan.magic == EVENT_MAGIC_SAVESTATE && scan.args[0].equals(id)) {
                     try {
                         if(scan.args.length > 1) {
                             rerecordCount = new BigInteger(scan.args[1]);
                             if(rerecordCount.signum() < 0)
                                 throw new NumberFormatException("Negative rerecord count not allowed");
                         }
                     } catch(NumberFormatException e) {
                         throw new IOException("Savestate rerecord count invalid");
                     }
                     break;
                 }
                 scan = scan.next;
             }
             if(scan == null)
                 throw new IOException("Savestate not compatible with event stream");

             if(scan.timestamp != expectedTime)
                 throw new IOException("Incorrect savestate event timestamp");

             current = scan;
         }

         try {
             Event scan = first;
             dispatchStart(aPC);
             boolean future = false;
             while(scan != null) {
                 if(scan == current)
                     future = true;
                 if(future)
                     scan.dispatch(aPC, EVENT_STATE_EFFECT_FUTURE);
                 else
                     scan.dispatch(aPC, EVENT_STATE_EFFECT);
                 scan = scan.next;
             }
             dispatchEnd(aPC);
         } catch(IOException e) {
             //Back off the changes.
             current = oldCurrent;
             throw e;
         }

         pc = aPC;
         sysClock = newSysClock;
         sysTimer = sysClock.newTimer(this);
         directMode = true;  //Assume direct mode on attach.
         timerInvokeTime = -1;  //No wait in progress.
         savestateRerecordCount = rerecordCount;
         attachTime = expectedTime;
         setClean();

         handleUndispatchedEvents();    //Do the events that occur after and simultaneously with savestate.
     }

     public void saveEvents(UTFOutputLineStream lines) throws IOException
     {
         Event scan = first;
         long lastTimestamp = 0;
         lines.encodeLine("0", "OPTION", "RELATIVE");
         while(scan != null) {
             if(scan.magic == EVENT_MAGIC_SAVESTATE) {
                lines.encodeLine(scan.timestamp - lastTimestamp, "SAVESTATE", scan.args[0], scan.args[1]);
             } else {
                 int extra = (scan.args != null) ? scan.args.length : 0;
                 Object[] arr = new Object[2 + extra];
                 arr[0] = new Long(scan.timestamp - lastTimestamp);
                 arr[1] = scan.clazz.getName();
                 if(extra > 0)
                     System.arraycopy(scan.args, 0, arr, 2, extra);
                 lines.encodeLine(arr);
             }
             lastTimestamp = scan.timestamp;
             scan = scan.next;
         }
     }

     public long getLastEventTime()
     {
         Event scan = first;
         long lastTimestamp = 0;
         while(scan != null) {
             if(scan.magic == EVENT_MAGIC_CLASS)
                 lastTimestamp = scan.timestamp;
             scan = scan.next;
         }
         return lastTimestamp;
     }

     public boolean isAtMovieEnd()
     {
         return (current == null);
     }

     public synchronized long getEventCount()
     {
         return (last != null) ? (last.sequenceNumber + 1) : 0;
     }

     public synchronized long getEventCurrentSequence()
     {
         return (current != null) ? current.sequenceNumber : -1;
     }

     private String getReturnClass(Event ev)
     {
         if(ev.magic == EVENT_MAGIC_CLASS)
             return ev.clazz.getName();
         else if(ev.magic == EVENT_MAGIC_SAVESTATE)
             return "SAVESTATE";
         else
             return "<BAD EVENT TYPE>";
     }

     private ReturnEvent convertToReturn(Event ev)
     {
         ReturnEvent evr = new ReturnEvent();
         evr.timestamp = ev.timestamp;
         if(ev.args == null)
             evr.eventData = new String[1];
         else {
             evr.eventData = new String[1 + ev.args.length];
             System.arraycopy(ev.args, 0, evr.eventData, 1, ev.args.length);
         }
         evr.eventData[0] = getReturnClass(ev);
         return evr;
     }

     private int sMinFour(long a, long b, long c, long d)
     {
         //Compute maximum, ignoring -'s.
         long max = a;
         max = (b > max) ? b : max;
         max = (c > max) ? c : max;
         max = (d > max) ? d : max;
         if(max < 0)
             return -1;

         //Now use max to get rid of -'s.
         a = (a >= 0) ? a : (max + 1);
         b = (b >= 0) ? b : (max + 1);
         c = (c >= 0) ? c : (max + 1);
         d = (d >= 0) ? d : (max + 1);

         long min = a;
         int mpos = 0;
         if(b < min) {
             min = b;
             mpos = 1;
         }
         if(c < min) {
             min = c;
             mpos = 2;
         }
         if(d < min) {
             min = d;
             mpos = 3;
         }
         return mpos;
     }

     public synchronized ReturnEvent getEventBySequence(long sequence)
     {
         if(sequence < 0 || last == null || sequence > last.sequenceNumber)
             return null;

         long distFirst = -1;
         long distLast = -1;
         long distCurrent = -1;
         long distCache = -1;

         if(first != null)
             distFirst = Math.abs(first.sequenceNumber - sequence);
         if(last != null)
             distLast = Math.abs(last.sequenceNumber - sequence);
         if(current != null)
             distCurrent = Math.abs(current.sequenceNumber - sequence);
         if(cache != null)
             distCache = Math.abs(cache.sequenceNumber - sequence);

         int minF = sMinFour(distFirst, distLast, distCurrent, distCache);

         //Find the nearest entrypoint.
         switch(minF) {
         case 0:
             cache = first;
             break;
         case 1:
             cache = last;
             break;
         case 2:
             cache = current;
             break;
         case 3:
             //Cache = cache;
             break;
         default:
             return null;   //Can't happen.
         }

         while(cache != null && sequence < cache.sequenceNumber)
             cache = cache.prev;
         while(cache != null && sequence > cache.sequenceNumber)
             cache = cache.next;
         if(cache == null)
             return null;
         return convertToReturn(cache);
     }

     public void callback()
     {
         handleUndispatchedEvents();
     }

     public int getTimerType()
     {
         return 17;
     }

     public void dumpStatus(StatusDumper output)
     {
     }

     public void dumpSRPartial(SRDumper output)
     {
     }
}
