package it.niedermann.android.markdown.markwon.plugins

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.MoreExecutors
import com.nextcloud.android.sso.model.SingleSignOnAccount
import com.nextcloud.android.sso.model.ocs.OcsResponse
import com.nextcloud.android.sso.model.ocs.OcsResponse.OcsWrapper
import com.nextcloud.android.sso.model.ocs.OcsUser
import io.mockk.every
import io.mockk.mockk
import it.niedermann.nextcloud.ocs.ApiProvider
import it.niedermann.nextcloud.ocs.OcsAPI
import junit.framework.TestCase
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Call
import retrofit2.Response
import java.util.*
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService

@RunWith(RobolectricTestRunner::class)
class NextcloudMentionsPluginTest : TestCase() {

    private lateinit var plugin: NextcloudMentionsPlugin
    private val mentions = mapOf(
        "foo" to "Foo Bidoo",
        "bar" to "Bar Iton",
        "baz" to "Baz Lightyear",
        "qux" to null,
    )

    @Before
    fun setup() {
        val mentionsConstructor = NextcloudMentionsPlugin::class.java.getDeclaredConstructor(
            Context::class.java,
            ApiProvider.Factory::class.java,
            ExecutorService::class.java
        )
        mentionsConstructor.isAccessible = true
        plugin = mentionsConstructor.newInstance(
            ApplicationProvider.getApplicationContext(),
            mockk<ApiProvider.Factory> {
                every {
                    createApiProvider<OcsAPI>(any(), any(), any(), any())
                } returns getApiProviderMock()
            },
            MoreExecutors.newDirectExecutorService()
        )
    }

    @Test
    fun findPotentialMentions() {
        setOf(null, "", "Lorem", "@", "@ ", " @", " @ ").forEach {
            val mentions = plugin.findPotentialMentions(it)
            assertEquals(0, mentions.size)
        }

        val mentions1 = plugin.findPotentialMentions("@foo")
        assertEquals(1, mentions1.size)
        assertEquals(true, mentions1.contains("foo"))

        val mentions2 = plugin.findPotentialMentions("@foo @bar")
        assertEquals(2, mentions2.size)
        assertEquals(true, mentions2.containsAll(listOf("foo", "bar")))

        val mentions3 = plugin.findPotentialMentions(" @foo @bar ")
        assertEquals(2, mentions3.size)
        assertEquals(true, mentions3.containsAll(listOf("foo", "bar")))

        val mentions4 = plugin.findPotentialMentions("@ @foo @bar tst @")
        assertEquals(2, mentions4.size)
        assertEquals(true, mentions4.containsAll(listOf("foo", "bar")))

        val mentions5 = plugin.findPotentialMentions("@ @foo@bar tst @")
        assertEquals(1, mentions5.size)
        assertEquals(true, mentions5.contains("foo"))
    }

    @Test
    fun fetchDisplayNames() {
        val displayNames = plugin.fetchDisplayNames(
            ApplicationProvider.getApplicationContext(),
            mockk<SingleSignOnAccount>(),
            setOf("foo", "bar", "qux")
        ).get()

        assertEquals(2, displayNames.size)
        assertEquals("Foo Bidoo", displayNames["foo"])
        assertEquals("Bar Iton", displayNames["bar"])
    }

    @Test
    fun fetchDisplayName() {
        val ocsApiMock = getOcsApiMock()

        val displayName1 = plugin.fetchDisplayName(ocsApiMock, "foo").get()
        assertEquals("Foo Bidoo", displayName1);

        try {
            plugin.fetchDisplayName(ocsApiMock, "qux").get()
            fail()
        } catch (e: ExecutionException) {
            assertEquals(RuntimeException::class, e.cause!!::class)
        }
    }

    private fun getApiProviderMock(): ApiProvider<OcsAPI> {
        return mockk<ApiProvider<OcsAPI>> {
            every { getApi() } returns getOcsApiMock()
        }
    }

    private fun getOcsApiMock(): OcsAPI {
        return mockk<OcsAPI> {
            every {
                getUser(any())
            } answers {
                val userName = firstArg<String>();
                mockk<Call<OcsResponse<OcsUser>>> {
                    every {
                        execute()
                    } returns mockk<Response<OcsResponse<OcsUser>>> {
                        every {
                            isSuccessful
                        } returns (mentions[userName] != null)
                        every {
                            body()
                        } answers {
                            val response = OcsResponse<OcsUser>()
                            response.ocs = OcsWrapper()
                            response.ocs.data = OcsUser()
                            response.ocs.data.displayName = mentions[userName]
                            response
                        }
                    }
                }
            }
        }
    }
}