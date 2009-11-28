import java.util.*;
import java.io.*;

class ExceptionDefProcessor
{
    private static Map<Class<?>, String> classes;

    private static void doClass(String line)
    {
        int split = line.indexOf(32);
        String clazz = line.substring(0, split);
        String desc = line.substring(split + 1);
        Class<?> classObject;

        try {
            classObject = Class.forName(clazz);
        } catch(Exception e) {
            System.err.println("Warning: Can't find class \"" + clazz + "\", dropping.");
            return;
        }
        classes.put(classObject, desc);
    }

    public static void main(String[] args)
    {
        classes = new HashMap<Class<?>, String>();

        if(args == null || args.length < 1) {
            System.err.println("Syntax: java ExceptionDefProcessor <inputfile>");
            return;
        }

        String autoexec = args[0];
        try {
            BufferedReader kbd2 = new BufferedReader(new InputStreamReader(
                new FileInputStream(autoexec), "UTF-8"));
            while(true) {
                String cmd = kbd2.readLine();
                if(cmd == null)
                    break;
                if(!cmd.equals(""))
                    doClass(cmd);
            }
        } catch (Exception e) {
            System.err.println("Failed to load exception defintions: " + e.getMessage());
        }

        Class<?> failingClass = null;
        do {
            if(failingClass != null)
                classes.put(failingClass, failingClass.getName());
            failingClass = null;
            for(Map.Entry<Class<?>, String> x : classes.entrySet()) {
                Class<?> superclass = x.getKey().getSuperclass();
                if(x.getKey().getName().equals("java.lang.Error") ||
                    x.getKey().getName().equals("java.lang.RuntimeException"))
                    continue;
                if(!classes.containsKey(superclass)) {
                    System.err.println("Warning: Missing superclass \"" + superclass.getName() + "\" for \"" +
                        x.getKey().getName() + "\".");
                    failingClass = superclass;
                    break;
                }
            }
        } while(failingClass != null);

        PrintStream stream = null;
        try {
            stream = new PrintStream("org/jpc/Exceptions.java", "UTF-8");
        } catch(Exception e) {
            System.err.println("Can't open org/jpc/Exceptions.java: " + e.getMessage());
            return;
        }

        stream.println("package org.jpc;");
        stream.println("import java.util.*;");
        stream.println("class Exceptions {");
        stream.println("public static Map<String,String> classes;");
        stream.println("static {");
        stream.println("classes = new HashMap<String,String>();");
        Class<?> out = null;
        String desc = null;
        do {
            if(out != null) {
                classes.remove(out);
                stream.println("classes.put(\"" + out.getName() + "\", \"" + desc + "\");");
            }
            out = null;
            for(Map.Entry<Class<?>, String> x : classes.entrySet()) {
                Class<?> superclass = x.getKey().getSuperclass();
                if(!classes.containsKey(superclass)) {
                    out = x.getKey();
                    desc = x.getValue();
                    break;
                }
            }
        } while(out != null);
        stream.println("}}");
        stream.close();
    }
}
