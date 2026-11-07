package de.felice.chipcardreader.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KohaCatalogTest {

    @Test
    fun `builds barcode search urls`() {
        assertEquals(
            "https://example.org/cgi-bin/koha/opac-search.pl?idx=bc&q=25020013&format=rss",
            KohaCatalog.rssUrl("https://example.org/", "25020013"),
        )
    }

    @Test
    fun `parses rss item with cdata and escaped html`() {
        // Shape of a Koha OPAC RSS answer (synthetic, not fetched from a real server).
        val rss = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
              <title>Katalog Suche: bc:25020013</title>
              <item>
                <title><![CDATA[Die unendliche Geschichte]]></title>
                <link>https://example.org/cgi-bin/koha/opac-detail.pl?biblionumber=42</link>
                <description>&lt;p&gt;Ende, Michael &amp;amp; Co.&lt;/p&gt;&lt;br/&gt; Thienemann,   1979</description>
              </item>
            </channel></rss>
        """.trimIndent()
        val hit = KohaCatalog.parseRss(rss).single()
        assertEquals("Die unendliche Geschichte", hit.title)
        assertEquals("Ende, Michael & Co. Thienemann, 1979", hit.details)
        assertTrue(hit.link!!.endsWith("biblionumber=42"))
    }

    @Test
    fun `empty result has no hits`() {
        assertTrue(KohaCatalog.parseRss("<rss><channel><title>x</title></channel></rss>").isEmpty())
    }
}
