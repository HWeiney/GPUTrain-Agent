package com.ruoyi.train.runner.process;

import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;

@FunctionalInterface
public interface ProcessLookup
{
    Optional<View> find(long pid);

    record View(long pid, Instant startedAt, String executable, List<String> arguments,
            boolean alive, BooleanSupplier destroy, BooleanSupplier destroyForcibly)
    {
        public View { arguments = arguments == null ? null : List.copyOf(arguments); }
    }

    static ProcessLookup system()
    {
        return pid -> ProcessHandle.of(pid).map(handle -> {
            var info = handle.info();
            return new View(handle.pid(), info.startInstant().orElse(null), info.command().orElse(null),
                info.arguments().map(List::of).orElse(null), handle.isAlive(), handle::destroy, handle::destroyForcibly);
        });
    }
}
