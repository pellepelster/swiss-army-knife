package io.pelle.sak.api.storage.model.internal

import java.io.IOException

// the blob was replaced since its size was taken
class BlobChangedException(
    path: BlobPath,
    expected: Long,
    actual: Long,
) : IOException("'$path' changed: $actual bytes instead of $expected")

