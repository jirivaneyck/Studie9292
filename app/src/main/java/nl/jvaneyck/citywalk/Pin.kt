package nl.jvaneyck.citywalk

data class Pin(
    val id: String,
    val lat: Double,
    val lon: Double,
    val time: Long,
    val note: String,
    /** File name inside [PinStore.photoDir], or null if the pin has no photo. */
    val photo: String?,
    val type: String = TYPE_PIN,
    /** Came in through a shared link from someone else. */
    val received: Boolean = false,
    /** Only for goals: the user has physically been there. */
    val reached: Boolean = false,
) {
    val isGoal get() = type == TYPE_GOAL

    companion object {
        const val TYPE_PIN = "pin"
        const val TYPE_GOAL = "goal"
    }
}
