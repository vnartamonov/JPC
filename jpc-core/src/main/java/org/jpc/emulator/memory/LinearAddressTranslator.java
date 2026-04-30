/*
    JPC: An x86 PC Hardware Emulator for a pure Java Virtual Machine
    Release Version 2.4

    A project from the Physics Dept, The University of Oxford

    Copyright (C) 2007-2010 The University of Oxford

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

    jpc.sourceforge.net
    or the developer website
    sourceforge.net/projects/jpc/

    End of licence header
*/

package org.jpc.emulator.memory;

import org.jpc.emulator.processor.Processor;

public final class LinearAddressTranslator
{
    private LinearAddressTranslator() {}

    public static int translateLinearAddressToInt(PhysicalAddressSpace physical, Processor proc, int offset)
    {
        if ((proc.getCR0() & 0x80000000) == 0)
            return offset;

        int baseAddress = proc.getCR3() & 0xFFFFF000;
        int directoryAddress = baseAddress | (0xFFC & (offset >>> 20));
        int directoryRawBits = physical.getDoubleWord(directoryAddress);

        boolean directoryPresent = (0x1 & directoryRawBits) != 0;
        if (!directoryPresent)
            return -1;

        boolean directoryIs4MegPage = ((0x80 & directoryRawBits) != 0) && ((proc.getCR4() & 0x10) != 0);

        if (directoryIs4MegPage)
        {
            int fourMegPageStartAddress = 0xFFC00000 & directoryRawBits;
            return fourMegPageStartAddress | (offset & 0x3FFFFF);
        }
        else
        {
            int directoryBaseAddress = directoryRawBits & 0xFFFFF000;
            int tableAddress = directoryBaseAddress | ((offset >>> 10) & 0xFFC);
            int tableRawBits = physical.getDoubleWord(tableAddress);

            boolean tablePresent = (0x1 & tableRawBits) != 0;
            if (!tablePresent)
                return -1;

            return tableRawBits & 0xFFFFF000;
        }
    }
}
