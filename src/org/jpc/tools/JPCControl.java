/*
    JPC: An x86 PC Hardware Emulator for a pure Java Virtual Machine

    Copyright (C) 2012-2013 Ian Preston

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

package org.jpc.tools;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;

public class JPCControl extends EmulatorControl
{
    private final Object pc;
    private final Method execute;
    private final Method setState;
    private final Method state;
    private final Method ints;
    private final Method instructionInfo;
    private final Method setPhysicalMemory;
    private final Method disam;
    private final Method x86Length;
    private final Method getPage;
    private final Method destroy;
    private final URLClassLoader cl1;

    public JPCControl(String jar, String pcName, String[] extraArgs) throws IOException
    {
        this(jar, concat(CompareToBochs.possibleArgs.get(pcName), extraArgs));
    }

    public JPCControl(String jar, String[] args) throws IOException
    {
        this(jar, args, false, false);
    }

    public JPCControl(String jar, String[] args, boolean showScreen, boolean disablePIT) throws IOException
    {
        URL[] urls1 = new URL[]{new File(jar).toURL()};
        cl1 = new URLClassLoader(urls1, EmulatorControl.class.getClassLoader());

        try {
            Class opts = cl1.loadClass("org.jpc.j2se.Option");
            Method parse = opts.getMethod("parse", String[].class);
            String[] pcargs = args;
            if (disablePIT)
                pcargs = concat(new String[]{"-bochs"}, args);
            parse.invoke(opts, (Object)pcargs);

            Class c1 = cl1.loadClass("org.jpc.emulator.PC");
            Constructor ctor = c1.getConstructor(String[].class);
            pc = ctor.newInstance((Object)pcargs);

            ints = c1.getMethod("checkInterrupts", Integer.class, Boolean.class);
            state = c1.getMethod("getState");
            setState = c1.getMethod("setState", int[].class);
            execute = c1.getMethod("executeBlock");
            instructionInfo = c1.getMethod("getInstructionInfo", Integer.class);
            setPhysicalMemory = c1.getMethod("setPhysicalMemory", Integer.class, byte[].class);
            disam = c1.getMethod("disam", byte[].class, Integer.class, Boolean.class);
            x86Length = c1.getMethod("x86Length", byte[].class, Boolean.class);
            destroy = c1.getMethod("destroy");
            getPage = c1.getMethod("savePage", Integer.class, byte[].class, Boolean.class);
            Method startClock = c1.getMethod("start");
            startClock.invoke(pc);

            if (showScreen)
            {
                JPanel screen = (JPanel)c1.getMethod("getNewMonitor").invoke(pc);
                JFrame frame = new JFrame();
                frame.getContentPane().add("Center", new JScrollPane(screen));
                frame.validate();
                frame.setVisible(true);
                frame.setBounds(100, 100, 760, 500);
            }
        } catch (ClassNotFoundException e) {throw new RuntimeException(e.getMessage());}
        catch (NoSuchMethodException e) {throw new RuntimeException(e.getMessage());}
        catch (IllegalAccessException e) {throw new RuntimeException(e.getMessage());}
        catch (InvocationTargetException e) {throw new RuntimeException(e.getMessage());}
        catch (InstantiationException e) {throw new RuntimeException(e.getMessage());}
    }

    public String disam(byte[] code, Integer ops, Boolean is32Bit)
    {
        try {
            return (String) disam.invoke(pc, code, ops, is32Bit);
        } catch (InvocationTargetException e)
        {
            if (e.getCause().getMessage().contains("Invalid"))
                return "invalid";
            return "Error during disam: " + e.getMessage();
        }
        catch (IllegalAccessException e) {throw new RuntimeException(e.getMessage());}
    }

    public int x86Length(byte[] code, Boolean is32Bit)
    {
        try {
            return (Integer) x86Length.invoke(pc, code, is32Bit);
        } catch (InvocationTargetException e)
        {
            return 0;
        }
        catch (IllegalAccessException e) {throw new RuntimeException(e.getMessage());}
    }

    public String executeInstruction() throws IOException
    {
        try {
            ints.invoke(pc, new Integer(1), new Boolean(false));
            int blockLength = (Integer)execute.invoke(pc);
            if (blockLength > 1)
                ints.invoke(pc, new Integer(blockLength - 1), new Boolean(false));
            return (String) instructionInfo.invoke(pc, new Integer(blockLength));
        } catch (InvocationTargetException e)
        {
            Throwable c = e.getCause();
            if (c instanceof IllegalStateException)
                return c.getMessage();
            if ((c != null) && c.toString().contains("PAGE_FAULT"))
                return c.toString();
            throw new RuntimeException(e.getMessage());
        }
        catch (IllegalAccessException e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    public int[] getState() throws IOException
    {
        try {
            return (int[])state.invoke(pc);
        } catch (InvocationTargetException e) {throw new RuntimeException(e.getMessage());}
        catch (IllegalAccessException e) {throw new RuntimeException(e.getMessage());}
    }

    public void setPhysicalMemory(int addr, byte[] data) throws IOException
    {
        try {
            setPhysicalMemory.invoke(pc, new Integer(addr), data);
        } catch (InvocationTargetException e) {throw new RuntimeException(e.getMessage());}
        catch (IllegalAccessException e) {throw new RuntimeException(e.getMessage());}
    }

    //public void setState(int[] state, int currentCSEIP) throws IOException
    //{
    //    try {
    //    setState.invoke(pc, (int[])state);
    //    } catch (InvocationTargetException e) {throw new RuntimeException(e.getMessage());}
    //    catch (IllegalAccessException e) {throw new RuntimeException(e.getMessage());}
    //}

    public void destroy()
    {
        try {
            destroy.invoke(pc);
        } catch (InvocationTargetException e)
        {
            throw new RuntimeException(e.getMessage());
        }
        catch (IllegalAccessException e) {throw new RuntimeException(e.getMessage());}
    }

    public Integer getPhysicalPage(Integer page, byte[] data) throws IOException
    {
        try {
            return (Integer) getPage.invoke(pc, page, data, new Boolean(false));
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public Integer getLinearPage(Integer page, byte[] data) throws IOException
    {
        try {
            return (Integer) getPage.invoke(pc, page, data, new Boolean(true));
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public byte[] getCMOS() throws IOException
    {
        throw new IllegalStateException("Unimplemented!");
    }
    public int[] getPit() throws IOException
    {
        throw new IllegalStateException("Unimplemented!");
    }
    public int getPITIntTargetEIP() throws IOException
    {
        throw new IllegalStateException("Unimplemented!");
    }
    public void keysDown(String keys)
    {
        throw new IllegalStateException("Unimplemented!");
    }
    public void keysUp(String keys)
    {
        throw new IllegalStateException("Unimplemented!");
    }
    public void sendMouse(Integer dx, Integer dy, Integer dz, Integer buttons)
    {
        throw new IllegalStateException("Unimplemented!");
    }

    public static <T> T[] concat(T[] first, T[] second) {
        T[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
}
}
