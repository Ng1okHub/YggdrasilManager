# YggdrasilManager Context

This glossary names the concepts the manager exposes while controlling Yggdrasil's process-observation and memory-presentation behavior.

## Language

**Maps policy**:
A set of rules that controls how Yggdrasil presents process memory mappings, either globally or for one process. A rule can hide a mapping or rewrite its permissions using a path, basename, or address-range match.
_Avoid_: Maps filter, memory filter

**Maps scope**:
The target to which a Maps policy applies: the global policy shared by all processes or the policy owned by one positive process identifier.
_Avoid_: Maps target, PID mode
