package io.pelle.sak.backend

import io.pelle.sak.model.ResourceId
import io.pelle.sak.api.storage.LocalStorage
import io.pelle.sak.api.storage.model.internal.BlobPath

// where the data of a row is in the storage of its kind: below its resource id, never below a name, an
// id of the database or the content (MODEL-8). The files, the buckets and the repositories of restic
// have a storage each, so the resource id is the first segment in all of them
fun ResourceId.blobPath(vararg below: String) = BlobPath(listOf(value) + below)

// whether nothing is in the storage at the resource id, so a new row may get it: a directory left by
// a restore of the instance keeps its id
fun LocalStorage.isFree(resourceId: ResourceId) = children(resourceId.blobPath()).isEmpty()

// the directories right below the root of the storage that are named as a resource id no row has, e.g.
// left by a restore of the instance (MODEL-8); they are kept, and no new row gets one of them. What is
// named otherwise is no data of a row
fun LocalStorage.leftovers(known: Set<ResourceId>): List<String> = leftovers(children(BlobPath(emptyList())), known)

// the names that are a resource id no row has, of the names right below the root of a storage
fun leftovers(
    names: List<String>,
    known: Set<ResourceId>,
): List<String> = names.filter { name -> ResourceId.of(name)?.let { it !in known } == true }
