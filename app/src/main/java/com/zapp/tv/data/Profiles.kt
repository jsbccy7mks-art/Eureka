package com.zapp.tv.data

import kotlinx.coroutines.flow.Flow

/** Profiles: who's watching. Profile 1 always exists and is the one Simkl syncs with. */
class ProfileStore(private val dao: ProfileDao, private val settings: Settings) {
    val all: Flow<List<Profile>> = dao.observeAll()

    suspend fun list(): List<Profile> = dao.all()

    suspend fun current(): Profile? = dao.all().firstOrNull { it.id == settings.profileId } ?: dao.all().firstOrNull()

    suspend fun add(name: String, color: Int, kids: Boolean): Long {
        val order = (dao.all().maxOfOrNull { it.sortOrder } ?: 0) + 1
        return dao.insert(Profile(name = name.trim().ifBlank { "Profile" }, color = color, kids = kids, pin = null, sortOrder = order, createdAt = System.currentTimeMillis()))
    }

    suspend fun update(p: Profile) = dao.update(p)

    suspend fun setPin(p: Profile, pin: String?) = dao.update(p.copy(pin = pin?.takeIf { it.length == 4 }?.let { Secrets.encrypt(it) }))

    fun pinMatches(p: Profile, entered: String): Boolean = p.pin == null || Secrets.decrypt(p.pin) == entered

    /** Deletes a profile with its progress and library. The first profile can't be deleted. */
    suspend fun delete(id: Long) {
        if (id == 1L) return
        dao.deleteProgress(id)
        dao.deleteLibrary(id)
        dao.delete(id)
        if (settings.profileId == id) settings.profileId = 1L
    }

    companion object {
        /**
         * Kids profiles only see titles marked as animation, family or kids, and never ones marked
         * horror, thriller, crime or war. Titles without genres are left out to be safe.
         */
        private val kidsGenres = setOf("animation", "family", "kids", "children", "anime")
        private val blocked = setOf("horror", "thriller", "crime", "war", "erotic", "adult")

        fun kidsSafe(genres: List<String>): Boolean {
            val g = genres.map { it.lowercase().trim() }
            return g.any { it in kidsGenres } && g.none { it in blocked }
        }
    }
}
