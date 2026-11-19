package modutils;

import javassist.bytecode.ClassFile;
import javassist.bytecode.ConstPool;
import javassist.bytecode.FieldInfo;
import javassist.bytecode.MethodInfo;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Locates classes inside a jar by name or by structural signature.
 *
 * Useful for class-replacement mods whose target class has an obfuscated name
 * that changes between game updates. Match on stable features that survive
 * reobfuscation: package prefix, implemented interfaces from @Keep types,
 * method signatures using @Keep parameter/return types, field descriptors,
 * and string constants in the constant pool.
 *
 * Example - find ProjectXChatDirector by name, fall back to signature:
 *   String fqn = new ClassFinder(jarPath)
 *       .packagePrefix("com.threerings.projectx.client.chat")
 *       .implementsInterface("com.threerings.projectx.data.ProjectXCodes")
 *       .findOrByName("com.threerings.projectx.client.chat.ProjectXChatDirector");
 *
 * Example - find an obfuscated dungeon-client class by signature only:
 *   String fqn = new ClassFinder(jarPath)
 *       .packagePrefix("com.threerings.projectx.dungeon.client")
 *       .implementsInterface("com.threerings.projectx.data.ProjectXCodes")
 *       .hasMethod("rereadPrefs", "(Lcom/threerings/projectx/client/ProjectXPrefs$c;)V")
 *       .hasMethod("a", "(IFFI)Lcom/threerings/tudey/data/InputFrame;")
 *       .hasFieldType("Lcom/threerings/projectx/dungeon/data/PartyObject;")
 *       .findUnique();
 */
public class ClassFinder {

    private final String jarPath;
    private String packagePrefix;          // internal form (slashes), with trailing '/'
    private String superClassDotted;       // dotted form, matches ClassFile.getSuperclass()
    private final List<String> interfacesDotted = new ArrayList<>();
    private final List<String[]> methodSigs = new ArrayList<>(); // [name, descriptor]
    private final List<String> fieldDescriptors = new ArrayList<>();
    private final List<String> stringConstants = new ArrayList<>();
    private boolean verbose = false;

    public ClassFinder(String jarPath) {
        this.jarPath = jarPath;
    }

    public ClassFinder verbose(boolean v) {
        this.verbose = v;
        return this;
    }

    public ClassFinder packagePrefix(String dotted) {
        String p = dotted.replace('.', '/');
        if (!p.isEmpty() && !p.endsWith("/")) p += "/";
        this.packagePrefix = p;
        return this;
    }

    public ClassFinder superClass(String dotted) {
        this.superClassDotted = dotted;
        return this;
    }

    public ClassFinder implementsInterface(String dotted) {
        this.interfacesDotted.add(dotted);
        return this;
    }

    /** Descriptor in JVM form, e.g. "(IFFI)Lcom/threerings/tudey/data/InputFrame;" */
    public ClassFinder hasMethod(String name, String descriptor) {
        this.methodSigs.add(new String[]{name, descriptor});
        return this;
    }

    /** Descriptor in JVM form, e.g. "Lcom/threerings/projectx/dungeon/data/PartyObject;" */
    public ClassFinder hasFieldType(String descriptor) {
        this.fieldDescriptors.add(descriptor);
        return this;
    }

    public ClassFinder hasStringConstant(String s) {
        this.stringConstants.add(s);
        return this;
    }

    /**
     * Try direct lookup by fully-qualified name first. If that class is not in
     * the jar, fall back to signature search. Use when the target class has a
     * stable name most of the time but you want resilience against renames.
     */
    public String findOrByName(String fqn) throws IOException {
        if (containsClass(fqn)) {
            if (verbose) System.out.println("[ClassFinder] direct hit: " + fqn);
            return fqn;
        }
        if (verbose) System.out.println("[ClassFinder] '" + fqn + "' missing, searching by signature");
        return findUnique();
    }

    /** Strict: signature must match exactly one class in the jar. */
    public String findUnique() throws IOException {
        List<String> hits = findAll();
        if (hits.isEmpty()) {
            throw new RuntimeException("ClassFinder: no class matched signature in " + jarPath);
        }
        if (hits.size() > 1) {
            throw new RuntimeException("ClassFinder: ambiguous signature, matched " + hits.size() + ": " + hits);
        }
        if (verbose) System.out.println("[ClassFinder] resolved: " + hits.get(0));
        return hits.get(0);
    }

    public List<String> findAll() throws IOException {
        List<String> hits = new ArrayList<>();
        try (JarFile jar = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                String name = e.getName();
                if (!name.endsWith(".class")) continue;
                if (packagePrefix != null && !name.startsWith(packagePrefix)) continue;
                // skip nested classes when scanning - they rarely have the same
                // shape as the outer class and only add noise
                String tail = name.substring(packagePrefix == null ? 0 : packagePrefix.length());
                if (tail.contains("$") || tail.indexOf('/') != -1) continue;

                ClassFile cf;
                try (DataInputStream in = new DataInputStream(jar.getInputStream(e))) {
                    cf = new ClassFile(in);
                } catch (Exception ex) {
                    continue;
                }
                if (matches(cf)) hits.add(cf.getName());
            }
        }
        return hits;
    }

    private boolean containsClass(String fqn) throws IOException {
        String entry = fqn.replace('.', '/') + ".class";
        try (JarFile jar = new JarFile(jarPath)) {
            return jar.getEntry(entry) != null;
        }
    }

    private boolean matches(ClassFile cf) {
        if (superClassDotted != null && !superClassDotted.equals(cf.getSuperclass())) return false;

        if (!interfacesDotted.isEmpty()) {
            Set<String> have = new HashSet<>(Arrays.asList(cf.getInterfaces()));
            for (String i : interfacesDotted) if (!have.contains(i)) return false;
        }

        if (!methodSigs.isEmpty()) {
            Set<String> have = new HashSet<>();
            for (Object o : cf.getMethods()) {
                MethodInfo mi = (MethodInfo) o;
                have.add(mi.getName() + mi.getDescriptor());
            }
            for (String[] sig : methodSigs) {
                if (!have.contains(sig[0] + sig[1])) return false;
            }
        }

        if (!fieldDescriptors.isEmpty()) {
            Set<String> have = new HashSet<>();
            for (Object o : cf.getFields()) {
                have.add(((FieldInfo) o).getDescriptor());
            }
            for (String d : fieldDescriptors) {
                if (!have.contains(d)) return false;
            }
        }

        if (!stringConstants.isEmpty()) {
            ConstPool cp = cf.getConstPool();
            Set<String> have = new HashSet<>();
            for (int i = 1; i < cp.getSize(); i++) {
                if (cp.getTag(i) == ConstPool.CONST_String) {
                    have.add(cp.getStringInfo(i));
                }
            }
            for (String s : stringConstants) {
                if (!have.contains(s)) return false;
            }
        }
        return true;
    }
}
