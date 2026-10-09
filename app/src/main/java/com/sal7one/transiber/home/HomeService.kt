package com.sal7one.transiber.home

/** Stable IDs survive future reordering; ordinals are never persisted. */
internal enum class HomeService(val id: String, val title: String, val description: String, val action: String) {
    CAPTIONS("captions", "Live captions", "Read or translate speech from videos and voices around you.", "Open captions");

    companion object {
        fun restore(id: String?): HomeService = entries.firstOrNull { it.id == id } ?: CAPTIONS
        fun forPage(page: Int): HomeService? = CAPTIONS.takeIf { page == 0 }
    }
}
