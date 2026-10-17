"""LiveCompanion core: protocol server, serializers, ops. Pure Python (no Live imports)."""
from .server import Core  # noqa: F401

# Bump on every protocol/op change; the app compares it (and the ops list) in sys.info and warns when Live runs an older script.
VERSION = 3
