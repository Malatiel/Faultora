package dev.faultora.extension.process;

/** Aggregate memory, fair-scheduler CPU bandwidth and optional task limits for one Linux assertion scope. */
public record ExtensionResourceLimits(int memoryMiB, int cpuPercent, Integer maxTasks) {
    public ExtensionResourceLimits(int memoryMiB, int cpuPercent) {
        this(memoryMiB, cpuPercent, null);
    }

    public ExtensionResourceLimits {
        if (maxTasks != null && maxTasks < 1) {
            throw new IllegalArgumentException("Resource task limits must be positive");
        }
        if (memoryMiB < 1 || cpuPercent < 1 || cpuPercent > 100) {
            throw new IllegalArgumentException("Resource limits require positive MiB and CPU in 1..100 percent");
        }
    }

    long memoryBytes() { return memoryMiB * 1048576L; }

    int quotaMicros() { return cpuPercent * 1000; }
}
