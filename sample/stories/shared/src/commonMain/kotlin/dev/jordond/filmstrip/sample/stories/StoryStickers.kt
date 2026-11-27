package dev.jordond.filmstrip.sample.stories

/**
 * The stickers the tray offers.
 *
 * @property label The name shown under the sticker and matched by the tray's filter.
 * @property styles How many looks the sticker has. A tap on a placed one steps through them.
 * @property keywords Other words the filter finds the sticker by, separated by spaces.
 */
internal enum class StoryStickerKind(
  val label: String,
  val styles: Int = 1,
  val keywords: String = "",
) {
  Heart("Heart", keywords = "love like"),
  Star("Star", keywords = "favourite rating"),
  Sparkle("Sparkle", keywords = "shine glitter"),
  Rec("Rec", keywords = "record camera badge"),
  Arrow("Arrow", keywords = "point pointer"),
  Clock("Clock", styles = 3, keywords = "time hour badge"),
  Day("Day", styles = 3, keywords = "date calendar weekend badge"),
  Bubble("Bubble", styles = 3, keywords = "speech talk say comment"),
  Tag("Tag", styles = 3, keywords = "label price new"),
}

/**
 * The stickers whose name or keywords hold [query], every sticker when it is blank. Case and the space around the
 * query are ignored.
 */
internal fun stickersMatching(query: String): List<StoryStickerKind> {
  val wanted = query.trim().lowercase()
  if (wanted.isEmpty()) return StoryStickerKind.entries
  return StoryStickerKind.entries.filter { wanted in it.label.lowercase() || wanted in it.keywords }
}

/**
 * The sticker one tap on from this one, on its next style and back to the first after the last.
 */
internal fun StoryContent.Sticker.stepped(): StoryContent.Sticker = copy(style = (style + 1) % kind.styles)
