# The model server address is pinned to 127.0.0.1

Every configured address for the local model server uses `127.0.0.1`, never `localhost`. On
Windows `localhost` resolves to the IPv6 loopback `::1` first, and a server bound only to
IPv4 then refuses the connection with an error that does not mention addressing at all. The
symptom is intermittent — it depends on the resolver's ordering — which makes it far more
expensive to diagnose than to prevent.

This is recorded because it looks like a stylistic choice and will otherwise be "corrected"
back to `localhost` by a well-meaning contributor or a formatter.

**Consequences**

The same rule applies to PostgreSQL, and to any container that needs to reach a host service:
from inside a container `127.0.0.1` is the container, so host-bound services are addressed
by the host gateway name instead. Nothing in this project calls the model from a container
in the default setup, but the migration runner may.