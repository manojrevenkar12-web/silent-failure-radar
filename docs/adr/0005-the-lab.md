# 5. A simulated organisation to compare designs on the same luck

## Context
None of this can be measured on a real company from outside it. Comparing designs on different random draws would
mix design effects with luck.

## Decision
`world/` simulates teams, services, a call graph, deploys, bugs, monitors, false alarms, customers and on-call
engineers. It holds what the radar cannot see (code complexity, which change carries a bug, when a customer would
complain) and reads only `config/world.json`. Every draw is keyed on the seed and the entity, so each design meets the
same bugs and the same customers. Development seeds set the gates; held-out seeds run once behind a lock.

## Consequences
The numbers say how the designs compare in this world, not what any real company would see. The world's rates are
assumptions written down in one file, so anyone who disagrees can change them and rerun.
