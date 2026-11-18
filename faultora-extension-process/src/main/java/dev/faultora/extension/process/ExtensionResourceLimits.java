package dev.faultora.extension.process;

/** Aggregate memory and fair-scheduler CPU bandwidth limits for one Linux assertion scope. */
public record ExtensionResourceLimits(int memoryMiB, int cpuPercent) {
    public ExtensionResourceLimits {
        if (memoryMiB < 1 || cpuPercent < 1 || cpuPercent > 100) {
            throw new IllegalArgumentException("Resource limits require positive MiB and CPU in 1..100 percent");
        }
    }

    long memoryBytes() { return memoryMiB * 1048576L; }

    int quotaMicros() { return cpuPercent * 1000; }
}
