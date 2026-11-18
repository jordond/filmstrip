package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi

// Every table here is the chunks a lossless or lossy WebP still carries after its RIFF header, with the VP8X chunk
// left off, so what is kept is the ALPH, VP8 and VP8L chunks of one frame, each padded to an even size. Each is
// made once from the PNG the tile or the lossy source describes:
//
//   cwebp -lossless -exact -z 9 tile.png -o tile.webp
//   cwebp -q 70 -alpha_q 100 -exact lossy.png -o lossy.webp
//
// The truth of a lossy still is the planes and the straight alpha samples `dwebp -nodither -yuv` and
// `dwebp -nodither -pam` write for it, both of libwebp 1.6.0.

/**
 * A lossy WebP still with the planes libwebp decodes it to and the samples it converts them to.
 *
 * Every plane and the [truth] are what `dwebp -nodither` writes, so [truth] is the picture's straight alpha samples as
 * red, green, blue and alpha, row by row.
 *
 * @property name What the still is, for test messages.
 * @property width The width of the picture in samples.
 * @property height The height of the picture in samples.
 */
@InternalFilmstripApi
public class LossyWebpStill internal constructor(
  public val name: String,
  public val width: Int,
  public val height: Int,
  internal val frame: String,
  private val luma: String,
  private val blueDifference: String,
  private val redDifference: String,
  private val alpha: String?,
  private val truth: String,
) {
  /**
   * The chunks the still holds ahead of any `VP8X`, an `ALPH` chunk when it has alpha and then its `VP8 ` chunk.
   */
  public fun frameChunks(): ByteArray = frame.hexBytes()

  /**
   * The luma plane, [width] by [height] samples.
   */
  public fun luma(): ByteArray = luma.hexBytes()

  /**
   * The blue difference plane, half the width and height rounded up.
   */
  public fun blueDifference(): ByteArray = blueDifference.hexBytes()

  /**
   * The red difference plane, half the width and height rounded up.
   */
  public fun redDifference(): ByteArray = redDifference.hexBytes()

  /**
   * The alpha plane, or null for a still with none.
   */
  public fun alpha(): ByteArray? = alpha?.hexBytes()

  /**
   * The red, green, blue and alpha samples libwebp converts the planes to, four a texel.
   */
  public fun truth(): ByteArray = truth.hexBytes()

  override fun toString(): String = name
}

/**
 * The lossy stills, one with every parity of width and height and one with no alpha.
 */
@InternalFilmstripApi
public object LossyWebpStills {
  /**
   * Eight by six with alpha.
   */
  public val EVEN: LossyWebpStill =
    LossyWebpStill(
      name = "even",
      width = 8,
      height = 6,
      frame =
        "414c5048220000000527a0b46d243686afd1a35f222206f9c240dab6a96cbfa045f43fc26aeaf4785603565038208200" +
          "0000d002009d012a0800060002403825b0027432803fa07b807eb702dfa9e756b800fe4cc132d1eac9a1f5087c8ad2a3" +
          "7065a8f006864fe055f46a4f461fe68ea19c88e2547b9dd07f9de75c7fdbc27b7d7e7bfd1c72078cc16be3ffe6cb511f" +
          "9a7f43dd7f2763b37fd93b86ce1fff8d8dc67fcd9aa40ffe73607eebffa9bffb46000000",
      luma =
        "ab426a8350599d9452607c7762c6979e5e727078938482cf5d6b88b38484c89b848f7a85a9c19ea29ca47b83c3be95a4",
      blueDifference =
        "c18b7e7cb49590239eaf538d",
      redDifference =
        "53a0a629d26f5ca2278d7330",
      alpha =
        "28476685a4c3e2395d7c9bbad9304f6e92b1d0ef466584a3c7e63d5c7b9ab9d834537291b0cfee456988a7c6e53c5b7a",
      truth =
        "6dc0ff28113ba3477e549b66bc679685862c4da4605051c34bd39ee20fe29239383fca5d554ec27c916ab29b98638eba" +
          "7f4d6ad9d4d6ca3061c4774f4ade716eab1dca92ad42cfb17e5da8d06c799bef829fb34674947565829a2084e5f74fa3" +
          "9828b8c79544bfe6917dcc3da6c2e85c5f99957b669e679ad1ec71b9abb71dd83e9ed0345fa0df537072ca728780b791" +
          "9dc19ab0a3ee9acf61cf81ee59d9904515e0e06948d2f1886975d3a79177b6c6c7dea4e59bef8e3c36ce995b2de8c77a",
    )

  /**
   * Seven by six with alpha, whose last column has no chroma of its own.
   */
  public val ODD_WIDTH: LossyWebpStill =
    LossyWebpStill(
      name = "odd width",
      width = 7,
      height = 6,
      frame =
        "414c5048210000000527a0b46d243686afd1a35f222206f9c240dab6a96cbba047f43f827a351d877a00565038208200" +
          "0000d002009d012a0700060002403825b0027435c03c8affb375300309649f034000fdbec18bceb8d7c96f8cdd201bf6" +
          "df6d3d4f8e0baa9810bf46aa022aae1fea8d8abf954db2fd4be1d31d58f33e7c7af51a74477dfe54563c18ae83e3ff4e" +
          "0e3ff9f55dbebafe346b2b8fb53fd1fffb1beee3ff6219521fff3e94bf7fc5ff0bf80000",
      luma =
        "ae456d865b62b35563807a67c8956175737b979387606e8bb78997cb888c8184aaca9e9cab7887c2bca4",
      blueDifference =
        "cf907091b68685238fb0388a",
      redDifference =
        "5f8db610cc7244ac2c947116",
      alpha =
        "28476685a4c3e25d7c9bbad9304f92b1d0ef466584c7e63d5c7b9ab934537291b0cfee6988a7c6e53c5b",
      truth =
        "83b4ff281c36bd476f5ead66ae749a859e3747a474584fc34ef3d0e24739e45d5c4cd67c8775bb9b8d708aba87565bd9" +
          "d8dac0304ec6854fad1fd792ad47d4b17e659ed0688589ef75b1a14675b1766584a12884972fb6c7974ab8e69782be3d" +
          "a9ccd55c50af7f7b65c4679acff56db946a6be34619ec9538078c8728a80a59198cd77b09eff82cf49de73ee1de1c169" +
          "58dbe388706ecaa79c7bafc6c6e57be58cfb613c28f4985b",
    )

  /**
   * Six by five with alpha, whose last row has no chroma of its own.
   */
  public val ODD_HEIGHT: LossyWebpStill =
    LossyWebpStill(
      name = "odd height",
      width = 6,
      height = 5,
      frame =
        "414c50481f0000000028476685a4c35d7c9bbad93092b1d0ef4665c7e63d5c7b9a34537291b0cf005650382078000000" +
          "5002009d012a0600050002403825b0027432803a4bc1846917a80000fc2fd8f1071aed14c9847d1dbe33b1ed9b871e66" +
          "f7807cf3fe317256cbfebd425feb93feb415777e261917fff8693f933f93933365ff5fffc695dff4e1ffbc5d49f47f8a" +
          "56b3b8a6d613bfa7fff593a9b533e9fff1eb7effc6000000",
      luma =
        "a7416d895a645162838177d361787a859ea2647495c498a59aa2858ebece",
      blueDifference =
        "c08e52ae75899eb426",
      redDifference =
        "5b7bcbca72282f7183",
      alpha =
        "28476685a4c35d7c9bbad93092b1d0ef4665c7e63d5c7b9a34537291b0cf",
      truth =
        "75b5ff280b3ca247586ca366a5818b85ae3618a4da3705c33d3cc55d5154be7c7984b09b888280ba9e6d4cd9ffd4a230" +
          "a825c592ac51c4b180758ed0679b7eef69c69b465fd2a0659a35b7c79955b7e69897b73da6e8d25c4bcd887b46e98d9a" +
          "5eb6e53472b9f153658cd172799ba991aee97ab0bdff5acf",
    )

  /**
   * Five by five with no alpha.
   */
  public val OPAQUE_ODD: LossyWebpStill =
    LossyWebpStill(
      name = "opaque odd",
      width = 5,
      height = 5,
      frame =
        "56503820740000003002009d012a0500050002403825b0027432803a4bc183469e7000ce3e9f4cb47ab2687a57e5a145" +
          "8b4e89be3bf858f75ffb7a84bfd727fcd05aeafc4c33a7fff8d8df933fb39334e1ff81fff1a577fd387ff833b6585ffe" +
          "295d1f92109b6a2cb0e346fff771dbe02ef9fffbe4cbd7ed1b800000",
      luma =
        "ad4a7a9a6a54688d8e7e607a818fac5f7398caa09ba68d99c6",
      blueDifference =
        "bc844bac639b99ad15",
      redDifference =
        "505bdbca7c15266f9f",
      alpha =
      null,
      truth =
        "6ac6ffff0056a0ff3c95a0ff99a98dffc7481aff3447c0ff4767b9ff68a2a8ff81a07dffa6774cffa327beffab57baff" +
          "87847cff6ead78ff6cdbb6ff9132abff9856aaffa39ba2ffb2f0c6ff4ed89aff54bedeff6fc4e9ff6d9ac8ff8ea8a4ff" +
          "caec73ff",
    )

  /**
   * Every lossy still.
   */
  public val ALL: List<LossyWebpStill> = listOf(EVEN, ODD_WIDTH, ODD_HEIGHT, OPAQUE_ODD)
}

/**
 * The chunks of the lossless tiles the animated fixtures paint their frames with, as hex.
 */
internal object WebpTileChunks {
  val RED: String =
    "5650384c0b0000002f0fc0020045410a1cfd0f00"

  val GREEN: String =
    "5650384c0c0000002f0bc002002870410ad2ff00"

  val GREEN_HOLE: String =
    "5650384c190000002f0bc002100ec26c237fabe3db09f4599082f47f027609ad3600"

  val BLUE_HALF: String =
    "5650384c0c0000002f07c001102850410a5ec000"

  val YELLOW: String =
    "5650384c0b0000002f0940020045c1a340fd0f00"

  val COUNTERS: List<String> =
    listOf(
      "5650384c210000002f1fc003000f30400332e0f31f78200b309199ccf8f2ad3b8388fee7ae6083dd0500",
      "5650384c230000002f1fc00300859a008420d1f42f65e5de75c007b4ff4282449257f88300b04e8882be0400",
      "5650384c250000002f1fc00300859ab69198403bfea47eec651df001edbf902091e415fd2000ff0c430314f42500",
      "5650384c260000002f1fc0030085a246929495b6fe4d311e7e077c40fb2f2448247905ff2000a88f20f416f44938",
      "5650384c270000002f1fc0030085a246929495b6fe4d311e7e077c40fb2f2420489a1df8070580a208c20f2ce093e200",
      "5650384c280000002f1fc00300859ab691a4401bfea4a6dcffae1df001edbf902091e415fd4100c119a301b4310dfd12",
      "5650384c280000002f1fc0030085a246929491b6fe4d311cdf77c007b4ff4282449257e80f02a03986f10360419f4403",
      "5650384c260000002f1fc0030085a246929495b6fe4d311e7e077c40fb2f2448247905ff2000288e20fc2de89370",
      "5650384c270000002f1fc00300859ab691988376fc49ddf6bdac033ea0fd17121024cd0efea000582584f150c0937200",
      "5650384c270000002f1fc00300859ab691988376fc49ddf6bdac033ea0fd17121024cd0efea0005825843150c0937200",
      "5650384c2a0000002f1fc00300859ab691988376fc49ddf6bdac033ea0fd579bb601134f698ff00701c0724218bf023d" +
        "0907",
      "5650384c2a0000002f1fc00300859ab691a48116fea4d2edde7fed800f68ff85048924aff00785501c8b70082cc2220b" +
        "fa25",
      "5650384c270000002f1fc0030095286924495a69ebdfd4f27b0d74a321033ea0fd9f80fe830b0441708889202cf4bb00",
      "5650384c280000002f1fc0030095286924495a69ebdfd4f27b0d74a321033ea0fd9f80fe830b044170888960b2d0ef02",
      "5650384c270000002f1fc00300859ab691988376fc49fdd4eb3ae003da7f214122c92bfc4100582708ea0028e8491c00",
      "5650384c260000002f1fc00300859ab691988376fc49ddf6bdac033ea0fd17122492bcc21f048075421857d09338",
      "5650384c270000002f1fc00300859ab691988376fc49fdd4eb3ae003da7f214122c92bfc4100a813040e8071057d0900",
      "5650384c270000002f1fc00300859ab691988376fc49fdd4eb3ae003da7f210141d2ece00f0a802a41b01f0a78520e00",
      "5650384c270000002f1fc00300859ab691988376fc49fdd4eb3ae003da7f210141d2ece00f0a802a41b0070a78520e00",
      "5650384c280000002f1fc0030095286924495a69ebdfd4f27b0d74a321033ea0fd9f80fe830b04c121826062b2d0ef02",
      "5650384c2b0000002f1fc00300859ab691988376fc49fdd4eb3ae003da7fb5691b30f194f6087f1000941304eb074081" +
        "9e840300",
      "5650384c290000002f1fc00300859ab691988376fc49fdd4eb3ae003da7fb5691b30f194f6087f1000941304fb57a027" +
        "e100",
      "5650384c2b0000002f1fc00300859a36929c81b6fc494d759f433be003da7f214122c92bf98300ca8964802c924532c0" +
        "827e0900",
      "5650384c2a0000002f1fc0030085a24692949136fe4d2d1fc377c007b4ff4282449257f80701504e1038005c10f22de8" +
        "9700",
      "5650384c270000002f1fc0030095286924495a69ebdfd4f27b0d74a321033ea0fd9f80fe830b04c1212682202cf4bb00",
      "5650384c280000002f1fc0030095286924495a69ebdfd4f27b0d74a321033ea0fd9f80fe830b04c121268260b2d0ef02",
      "5650384c280000002f1fc0030095286924495a69ebdfd4f27b0d74a321033ea0fd9f80fe830b04c121268289b0d0ef02",
      "5650384c280000002f1fc0030085a246929495b6fe4d311e7e077c40fb2f2420489a1df80705805004a1950c2ce093e2",
      "5650384c280000002f1fc0030085a246929495b6fe4d311e7e077c40fb2f2420489a1df80705805004e1cb6f2ce093e2",
      "5650384c280000002f1fc0030085a246929495b6fe4d311e7e077c40fb2f2420489a1df80705805004e1cb3f2ce093e2",
      "5650384c270000002f1fc00300859ab691988376fc49fdd4eb3ae003da7f214122c92bfc4100a81304eb0028e8491c00",
      "5650384c260000002f1fc00300859ab691988376fc49fdd4eb3ae003da7f214122c92bfc4100a81304fb0a7a1207",
      "5650384c280000002f1fc003009530892449d24a5bffa6963f7767200b1d19f001edff04cc3ff040b4f8232262a3af0b",
      "5650384c270000002f1fc0030095286924495a69ebdfd4f27b0d74a321033ea0fd9f80fe830b0487088220982cf4bb00",
      "5650384c270000002f1fc0030095286924495a69ebdfd4f27b0d74a321033ea0fd9f80fe830b0487088260222cf4bb00",
      "5650384c280000002f1fc0030095286924495a69ebdfd4f27b0d74a321033ea0fd9f80fe830b048708826062b2d0ef02",
      "5650384c270000002f1fc0030085a246929495b6fe4d311e7e077c40fb2f2420489a1df80705002b82280716f0497100",
      "5650384c280000002f1fc0030095286924495a69ebdfd4f27b0d74a321033ea0fd9f80fe830b048708828960b2d0ef02",
      "5650384c280000002f1fc0030095286924495a69ebdfd4f27b0d74a321033ea0fd9f80fe830b048708828989b0d0ef02",
      "5650384c280000002f1fc0030085a246929495b6fe4d311e7e077c40fb2f2420489a1df80705002b8248067c2de093e2",
      "5650384c270000002f1fc0030095286924495a69ebdfd4f27b0d74a321033ea0fd9f80fe830b0487082682202cf4bb00",
      "5650384c280000002f1fc0030095286924495a69ebdfd4f27b0d74a321033ea0fd9f80fe830b048708268260b2d0ef02",
      "5650384c280000002f1fc0030095286924495a69ebdfd4f27b0d74a321033ea0fd9f80fe830b048708268289b0c02f03",
      "5650384c290000002f1fc003009530892449d24a5bffa6967f7706aa5091011fd0fe4fc0fc070f105c245882656df097" +
        "0100",
      "5650384c280000002f1fc0030095286924495a69ebdfd4f27b0d74a321033ea0fd9f80fe830b048708262682b0d0ef02",
      "5650384c290000002f1fc003009530892449d24a5bffa6967f7706aa5091011fd0fe4fc0fc070f105c245896606df477" +
        "0100",
      "5650384c290000002f1fc003009530892449d24a5bffa6967f7706aa5091011fd0fe4fc0fc070f105c245896256cf477" +
        "0100",
      "5650384c2a0000002f1fc00300859ab691a4400b7f52d3edde7fed800f68ff85048924aff80701388087e0001e42710b" +
        "fa25",
      "5650384c270000002f1fc0030095286924495a69ebdfd4f27b0d74a321033ea0fd9f80fe830b0487980882202cf4bb00",
      "5650384c280000002f1fc0030095286924495a69ebdfd4f27b0d74a321033ea0fd9f80fe830b048798088260b2d0ef02",
      "5650384c280000002f1fc0030095286924495a69ebdfd4f27b0d74a321033ea0fd9f80fe830b048798088289b0d0ef02",
      "5650384c280000002f1fc0030085a246929495b6fe4d311e7e077c40fb2f2420489a1df80705002b82d0ca1f16f04971",
      "5650384c280000002f1fc0030095286924495a69ebdfd4f27b0d74a321033ea0fd9f80fe830b048798082682b0d0ef02",
      "5650384c290000002f1fc003009530892449d24a5bffa6967f7706aa5091011fd0fe4fc0fc070f105c640996606df477" +
        "0100",
      "5650384c270000002f1fc0030035a8691b491a68c39fd4f6f7b6c9800f68ff27a07f70a007fa204aa02b4a60815f0600",
      "5650384c260000002f1fc0030035a8691b491a68c39fd4f6f7b6c9800f68ff27a07f70a007fa204aa02b8a2ef4bb",
      "5650384c290000002f1fc0030085a246929491b6fe4d311cdf77c007b4ff4282441357e80f0e808b619200885ad027a7" +
        "0100",
      "5650384c280000002f1fc0030085a246929495b6fe4d311e7e077c40fb2f2420489a1df80705002b82f0956c2ce093e2",
      "5650384c290000002f1fc003009530892449d24a5bffa6967f7706aa5091011fd0fe4fc0fc070f105c645982256cf477" +
        "0100",
      "5650384c260000002f1fc0030035a8691b491a68c39fd4f6f7b6c9800f68ff27a07f70a007fa208a12e88a2ef4bb",
    )
}
