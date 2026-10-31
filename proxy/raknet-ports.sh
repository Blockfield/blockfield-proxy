# shellcheck shell=bash
# RakNet UDP ports for the Blockfield proxy, resolved here at container start.
# Same idea as itzg's mc-image-helper interpolation of velocity.toml, but for
# JVM flags (which live on the java command line, not in a file): env vars
# with baked-in defaults, so Dokploy shows and can change them without an
# image rebuild, and unset means the prod defaults. Sourced (not executed) by
# start-proxy.sh, which appends RAKNET_JVM_OPTS to JVM_OPTS; also sourceable
# in tests. The Blockfield plugin routes by BLOCKFIELD_RAKNET_WORKSHOP_PORT
# (default 25567): keep the values in sync, a Workshop port the plugin does
# not know routes to Game.
: "${BLOCKFIELD_RAKNET_GAME_PORT:=25566}"
: "${BLOCKFIELD_RAKNET_WORKSHOP_PORT:=25567}"
RAKNET_JVM_OPTS="-Draknetify.velocity.portOverride=${BLOCKFIELD_RAKNET_GAME_PORT} -Draknetify.velocity.workshopPort=${BLOCKFIELD_RAKNET_WORKSHOP_PORT}"
export RAKNET_JVM_OPTS
