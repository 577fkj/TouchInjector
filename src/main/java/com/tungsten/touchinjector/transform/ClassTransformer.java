package com.tungsten.touchinjector.transform;

import com.tungsten.touchinjector.Config;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.tungsten.touchinjector.util.Logging.Level.*;
import static com.tungsten.touchinjector.util.Logging.log;
import static java.util.Collections.emptyList;
import static org.objectweb.asm.Opcodes.ACC_INTERFACE;
import static org.objectweb.asm.Opcodes.H_INVOKESTATIC;

public class ClassTransformer implements ClassFileTransformer {

    public final List<TransformUnit> units = new CopyOnWriteArrayList<>();
    public final List<ClassLoadingListener> listeners = new CopyOnWriteArrayList<>();
    public final PerformanceMetrics performanceMetrics = new PerformanceMetrics();

    private class TransformHandle {

        private class TransformContextImpl implements TransformContext {

            public boolean modifiedMark;
            public int minVersionMark = -1;
            public int upgradedVersionMark = -1;
            public boolean callbackMetafactoryRequested = false;

            @Override
            public void markModified() {
                modifiedMark = true;
            }

            @Override
            public void requireMinimumClassVersion(int version) {
                if (this.minVersionMark < version) {
                    this.minVersionMark = version;
                }
            }

            @Override
            public void upgradeClassVersion(int version) {
                if (this.upgradedVersionMark < version) {
                    this.upgradedVersionMark = version;
                }
            }

            @Override
            public Handle acquireCallbackMetafactory() {
                this.callbackMetafactoryRequested = true;
                return new Handle(
                        H_INVOKESTATIC,
                        className.replace('.', '/'),
                        CallbackSupport.METAFACTORY_NAME,
                        CallbackSupport.METAFACTORY_SIGNATURE,
                        TransformHandle.this.isInterface());
            }

            @Override
            public List<String> getStringConstants() {
                return TransformHandle.this.getStringConstants();
            }
        }

        private final String className;
        private final ClassLoader classLoader;
        private byte[] classBuffer;
        private ClassReader cachedClassReader;
        private List<String> cachedConstants;

        private List<TransformUnit> appliedTransformers;
        private int minVersion = -1;
        private int upgradedVersion = -1;
        private boolean addCallbackMetafactory = false;

        public TransformHandle(ClassLoader classLoader, String className, byte[] classBuffer) {
            this.className = className;
            this.classBuffer = classBuffer;
            this.classLoader = classLoader;
        }

        private ClassReader getClassReader() {
            if (cachedClassReader == null)
                cachedClassReader = new ClassReader(classBuffer);
            return cachedClassReader;
        }

        private boolean isInterface() {
            return (getClassReader().getAccess() & ACC_INTERFACE) != 0;
        }

        private List<String> getStringConstants() {
            if (cachedConstants == null)
                cachedConstants = extractStringConstants(getClassReader());
            return cachedConstants;
        }

        public void accept(TransformUnit... units) {
            long t0 = System.nanoTime();

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);

            TransformContextImpl[] ctxs = new TransformContextImpl[units.length];
            ClassVisitor chain = writer;
            for (int i = units.length - 1; i >= 0; i--) {
                TransformContextImpl ctx = new TransformContextImpl();
                Optional<ClassVisitor> visitor = units[i].transform(classLoader, className, chain, ctx);
                if (!visitor.isPresent())
                    continue;
                ctxs[i] = ctx;
                chain = visitor.get();
            }

            long t1 = System.nanoTime();
            synchronized (performanceMetrics) {
                performanceMetrics.scanTime += t1 - t0;
            }

            if (chain == writer)
                return;

            t0 = System.nanoTime();

            getClassReader().accept(chain, 0);

            t1 = System.nanoTime();
            synchronized (performanceMetrics) {
                performanceMetrics.analysisTime += t1 - t0;
            }

            boolean modified = false;
            for (int i = 0; i < units.length; i++) {
                TransformContextImpl ctx = ctxs[i];
                if (ctx == null || !ctx.modifiedMark)
                    continue;

                log(INFO, "Transformed [" + className + "] with [" + units[i] + "]");

                if (appliedTransformers == null)
                    appliedTransformers = new ArrayList<>();
                appliedTransformers.add(units[i]);

                if (ctx.minVersionMark > this.minVersion) {
                    this.minVersion = ctx.minVersionMark;
                }
                if (ctx.upgradedVersionMark > this.upgradedVersion) {
                    this.upgradedVersion = ctx.upgradedVersionMark;
                }
                this.addCallbackMetafactory |= ctx.callbackMetafactoryRequested;

                modified = true;
            }

            if (modified) {
                classBuffer = writer.toByteArray();
                cachedClassReader = null;
                cachedConstants = null;
            }
        }

        public Optional<byte[]> finish() {
            if (appliedTransformers == null || appliedTransformers.isEmpty()) {
                return Optional.empty();
            } else {
                if (addCallbackMetafactory) {
                    accept(new CallbackMetafactoryTransformer());
                }
                if (minVersion == -1 && upgradedVersion == -1) {
                    return Optional.of(classBuffer);
                } else {
                    try {
                        accept(new ClassVersionTransformUnit(minVersion, upgradedVersion));
                        return Optional.of(classBuffer);
                    } catch (ClassVersionException e) {
                        log(WARNING, "Skipping [" + className + "], " + e.getMessage());
                        return Optional.empty();
                    }
                }
            }
        }

        public List<TransformUnit> getAppliedTransformers() {
            return appliedTransformers == null ? emptyList() : appliedTransformers;
        }

        public byte[] getFinalResult() {
            return classBuffer;
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String internalClassName, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
        if (internalClassName != null && classfileBuffer != null) {
            try {
                long t0 = System.nanoTime();

                String className = internalClassName.replace('/', '.');

                long t1 = System.nanoTime();

                TransformHandle handle = new TransformHandle(loader, className, classfileBuffer);
                TransformUnit[] unitsArray = units.toArray(new TransformUnit[0]);
                handle.accept(unitsArray);

                Optional<byte[]> transformResult = handle.finish();
                if (Config.printUntransformedClass && !transformResult.isPresent()) {
                    log(DEBUG, "No transformation is applied to [" + className + "]");
                }

                listeners.forEach(it -> it.onClassLoading(loader, className, handle.getFinalResult(), handle.getAppliedTransformers()));

                long t2 = System.nanoTime();

                synchronized (performanceMetrics) {
                    performanceMetrics.classesScanned++;
                    performanceMetrics.totalTime += t2 - t0;
                    performanceMetrics.matchTime += t1 - t0;
                }

                return transformResult.orElse(null);
            } catch (Throwable e) {
                log(WARNING, "Failed to transform [" + internalClassName + "]", e);
            }
        }
        return null;
    }

    private static List<String> extractStringConstants(ClassReader reader) {
        List<String> constants = new ArrayList<>();
        int constantPoolSize = reader.getItemCount();
        char[] buf = new char[reader.getMaxStringLength()];
        for (int idx = 1; idx < constantPoolSize; idx++) {
            int offset = reader.getItem(idx);
            if (offset == 0)
                continue;
            int type = reader.readByte(offset - 1);
            if (type == 8) { // CONSTANT_String_info
                String constant = (String) reader.readConst(idx, buf);
                constants.add(constant);
            }
        }
        return constants;
    }

}
