/*
    JPC-RR: A x86 PC Hardware Emulator
    Release 1

    Copyright (C) 2007-2009 Isis Innovation Limited
    Copyright (C) 2009 H. Ilari Liusvaara

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

package org.jpc.emulator.memory.codeblock;

import org.jpc.emulator.processor.Processor;

/**
 * A single chunk of executable code.
 * <p>
 * An instance of this interface describes a transformation on a processor state
 * and represents the action of a sequence of x86 operations.
 * @author Chris Dennis
 */
public interface CodeBlock
{
    /**
     * Gets the length in bytes of this codeblock.
     * <p>
     * This is the length of the section of x86 machine code that this
     * object was decoded from.
     * @return length of equivalent x86 in bytes.
     */
    public int getX86Length();

    /**
     * Gets the count of x86 instructions in this codeblock.
     * @return count of equivalent x86 instructions.
     */
    public int getX86Count();

    /**
     * Execute this codeblock on the given processor state.
     * <p>
     * The number of equivalent x86 instructions executed is returned, or a
     * negative value should an error occur.  If execution of this block
     * terminates abruptly (for example due to a processor exception) then the
     * return value may not equal the return of <code>getX86Count</code>
     * @param cpu state on which to execute.
     * @return the number of x86 instructions executed or negative on error.
     */
    public int execute(Processor cpu);

    public String getDisplayString();

    /**
     * Returns true if this block can automatically adapt to its backing
     * memory region being changed.
     * <p>
     * If modification of memory within the given range causes this block to
     * become invalid then returns <code>false</code>.
     * @param startAddress inclusive start address of memory region
     * @param endAddress exclusive end address of memory region
     * @return <code>true</code> if this block is invalid
     */
    public boolean handleMemoryRegionChange(int startAddress, int endAddress);

    /**
     * Informs this codeblock that its backing region has changed.
     */
    public void invalidate();
}