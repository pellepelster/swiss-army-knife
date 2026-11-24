package io.pelle.sak.storage.model

import kotlinx.io.IOException

// the blob was replaced since its size was taken
class BlobChangedException(
    path: BlobPath,
    expected: Long,
    actual: Long,
) : IOException("'$path' changed: $actual bytes instead of $expected")

