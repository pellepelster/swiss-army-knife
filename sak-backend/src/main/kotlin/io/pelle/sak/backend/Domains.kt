package io.pelle.sak.backend

// labels of letters, digits and hyphens, not starting or ending with a hyphen
private val LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

// whether the name is one label of a domain, as the names of the repositories of restic and of the
// backup plans are
fun isDnsLabel(name: String): Boolean = LABEL.matches(name)

// a lower case domain of at least two labels, whose last one is no number
fun isValidDomain(domain: String): Boolean {
    val labels = domain.split('.')
    return domain.length <= 253 && labels.size >= 2 && labels.all { LABEL.matches(it) } && !labels.last().all { it.isDigit() }
}
