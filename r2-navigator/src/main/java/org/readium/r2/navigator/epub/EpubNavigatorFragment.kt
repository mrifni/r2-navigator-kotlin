package org.readium.r2.navigator.epub

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PointF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.viewpager.widget.ViewPager
import kotlinx.coroutines.*
import org.readium.r2.navigator.*
import org.readium.r2.navigator.extensions.layoutDirectionIsRTL
import org.readium.r2.navigator.extensions.positionsByResource
import org.readium.r2.navigator.pager.R2EpubPageFragment
import org.readium.r2.navigator.pager.R2PagerAdapter
import org.readium.r2.navigator.pager.R2ViewPager
import org.readium.r2.shared.COLUMN_COUNT_REF
import org.readium.r2.shared.SCROLL_REF
import org.readium.r2.shared.getAbsolute
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.ReadingProgression
import org.readium.r2.shared.publication.epub.EpubLayout
import org.readium.r2.shared.publication.presentation.presentation
import org.readium.r2.shared.publication.services.positions
import kotlin.coroutines.CoroutineContext
import kotlin.math.ceil

class EpubNavigatorFragment(
        internal val publication: Publication,
        private val initialLocator: Locator? = null,
        internal val listener: Navigator.Listener? = null,
        private val baseUrl: String? = null
): Fragment(), CoroutineScope, VisualNavigator, R2BasicWebView.Listener {

    /**
     * Context of this scope.
     */
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main


    lateinit var positions: List<Locator>

    private lateinit var resourcesSingle: ArrayList<Pair<Int, String>>
    private lateinit var resourcesDouble: ArrayList<Triple<Int, String, String>>

    internal lateinit var preferences: SharedPreferences
    internal lateinit var resourcePager: R2ViewPager
    internal lateinit var publicationIdentifier: String

    var currentPagerPosition: Int = 0
    lateinit var adapter: R2PagerAdapter
    lateinit var currentActivity: FragmentActivity

    protected var navigatorDelegate: NavigatorDelegate? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        currentActivity = requireActivity()
        val view = inflater.inflate(R.layout.activity_r2_viewpager, container, false)

        preferences = currentActivity.getSharedPreferences("org.readium.r2.settings", Context.MODE_PRIVATE)

        resourcePager = view.findViewById(R.id.resourcePager)
        resourcePager.type = Publication.TYPE.EPUB

        resourcesSingle = ArrayList()
        resourcesDouble = ArrayList()

        positions = runBlocking { publication.positions() }
        publicationIdentifier = publication.metadata.identifier!!

        val supportFragmentManager = currentActivity.supportFragmentManager

        // TODO needs work, currently showing two resources for fxl, needs to understand which two resources, left & right, or only right etc.
        var doublePageIndex = 0
        var doublePageLeft = ""
        var doublePageRight = ""
        var resourceIndexDouble = 0

        for ((resourceIndexSingle, spineItem) in publication.readingOrder.withIndex()) {
            val uri: String = baseUrl + spineItem.href
            resourcesSingle.add(Pair(resourceIndexSingle, uri))

            // add first page to the right,
            if (resourceIndexDouble == 0) {
                doublePageLeft = ""
                doublePageRight = uri
                resourcesDouble.add(Triple(resourceIndexDouble, doublePageLeft, doublePageRight))
                resourceIndexDouble++
            } else {
                // add double pages, left & right
                if (doublePageIndex == 0) {
                    doublePageLeft = uri
                    doublePageIndex = 1
                } else {
                    doublePageRight = uri
                    doublePageIndex = 0
                    resourcesDouble.add(Triple(resourceIndexDouble, doublePageLeft, doublePageRight))
                    resourceIndexDouble++
                }
            }
        }
        // add last page if there is only a left page remaining
        if (doublePageIndex == 1) {
            doublePageIndex = 0
            resourcesDouble.add(Triple(resourceIndexDouble, doublePageLeft, ""))
        }


        if (publication.metadata.presentation.layout == EpubLayout.REFLOWABLE) {
            adapter = R2PagerAdapter(supportFragmentManager, resourcesSingle, publication.metadata.title, Publication.TYPE.EPUB)
            resourcePager.type = Publication.TYPE.EPUB
        } else {
            resourcePager.type = Publication.TYPE.FXL
            adapter = when (preferences.getInt(COLUMN_COUNT_REF, 0)) {
                1 -> {
                    R2PagerAdapter(supportFragmentManager, resourcesSingle, publication.metadata.title, Publication.TYPE.FXL)
                }
                2 -> {
                    R2PagerAdapter(supportFragmentManager, resourcesDouble, publication.metadata.title, Publication.TYPE.FXL)
                }
                else -> {
                    // TODO based on device
                    // TODO decide if 1 page or 2 page
                    R2PagerAdapter(supportFragmentManager, resourcesSingle, publication.metadata.title, Publication.TYPE.FXL)
                }
            }
        }
        resourcePager.adapter = adapter

        resourcePager.direction = publication.contentLayout.readingProgression

        if (publication.cssStyle == ReadingProgression.RTL.value) {
            resourcePager.direction = ReadingProgression.RTL
        }

        resourcePager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {

            override fun onPageSelected(position: Int) {
//                if (publication.metadata.presentation.layout == EpubLayout.REFLOWABLE) {
//                    resourcePager.disableTouchEvents = true
//                }
                if (preferences.getBoolean(SCROLL_REF, false)) {
                    if (currentPagerPosition < position) {
                        // handle swipe LEFT
                        currentFragment?.webView?.scrollToStart()
                    } else if (currentPagerPosition > position) {
                        // handle swipe RIGHT
                        currentFragment?.webView?.scrollToEnd()
                    }
                } else {
                    if (currentPagerPosition < position) {
                        // handle swipe LEFT
                        currentFragment?.webView?.setCurrentItem(0, false)
                    } else if (currentPagerPosition > position) {
                        // handle swipe RIGHT
                        currentFragment?.webView?.apply {
                            setCurrentItem(numPages - 1, false)
                        }
                    }
                }
                currentPagerPosition = position // Update current position

                notifyCurrentLocation()
            }

        })

        if (initialLocator != null) {
            go(initialLocator)
        }

        return view
    }

    override fun onResume() {
        super.onResume()
        notifyCurrentLocation()
    }

    /**
     * Locator waiting to be loaded in the navigator.
     */
    internal var pendingLocator: Locator? = null

    override fun go(locator: Locator, animated: Boolean, completion: () -> Unit): Boolean {
        pendingLocator = locator

        // href is the link to the page in the toc
        var href = locator.href

        if (href.indexOf("#") > 0) {
            href = href.substring(0, href.indexOf("#"))
        }

        fun setCurrent(resources: ArrayList<*>) {
            for (resource in resources) {
                if (resource is Pair<*, *>) {
                    resource as Pair<Int, String>
                    if (resource.second.endsWith(href)) {
                        if (resourcePager.currentItem == resource.first) {
                            // reload webview if it has an anchor
                            locator.locations.fragments.firstOrNull()?.let { fragment ->

                                val fragments = fragment.split(",").associate {
                                    val (left, right) = it.split("=")
                                    left to right.toInt()
                                }
                                //            val id = fragments.getValue("id")
                                if (fragments.isEmpty()) {
                                    var anchor = fragment
                                    if (!anchor.startsWith("#")) {
                                        anchor = "#$anchor"
                                    }
                                    val goto = resource.second + anchor
                                    currentFragment?.webView?.loadUrl(goto)
                                } else {
                                    currentFragment?.webView?.loadUrl(resource.second)
                                }

                            } ?: run {
                                currentFragment?.webView?.loadUrl(resource.second)
                            }
                        } else {
                            resourcePager.currentItem = resource.first
                        }
                        break
                    }
                } else {
                    resource as Triple<Int, String, String>
                    if (resource.second.endsWith(href) || resource.third.endsWith(href)) {
                        resourcePager.currentItem = resource.first
                        break
                    }
                }
            }
        }

        resourcePager.adapter = adapter

        if (publication.metadata.presentation.layout == EpubLayout.REFLOWABLE) {
            setCurrent(resourcesSingle)
        } else {

            when (preferences.getInt(COLUMN_COUNT_REF, 0)) {
                1 -> {
                    setCurrent(resourcesSingle)
                }
                2 -> {
                    setCurrent(resourcesDouble)
                }
                else -> {
                    // TODO based on device
                    // TODO decide if 1 page or 2 page
                    setCurrent(resourcesSingle)
                }
            }
        }

        return true
    }

    override fun go(link: Link, animated: Boolean, completion: () -> Unit): Boolean {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onProgressionChanged(progression: Double) {
        notifyCurrentLocation()
    }

    override fun goForward(animated: Boolean, completion: () -> Unit): Boolean {
        launch {
            if (resourcePager.currentItem < resourcePager.adapter!!.count - 1) {

                resourcePager.setCurrentItem(resourcePager.currentItem + 1, animated)

                if (currentFragment?.activity?.layoutDirectionIsRTL() ?: publication.contentLayout.readingProgression == ReadingProgression.RTL) {
                    // The view has RTL layout
                    currentFragment?.webView?.apply {
                        progression = 1.0
                        setCurrentItem(numPages - 1, false)
                    }
                } else {
                    // The view has LTR layout
                    currentFragment?.webView?.apply {
                        progression = 0.0
                        setCurrentItem(0, false)
                    }
                }
            }
        }
        return true
    }

    override fun goBackward(animated: Boolean, completion: () -> Unit): Boolean {
        launch {
            if (resourcePager.currentItem > 0) {

                resourcePager.setCurrentItem(resourcePager.currentItem - 1, animated)

                if (currentFragment?.activity?.layoutDirectionIsRTL() ?: publication.contentLayout.readingProgression == ReadingProgression.RTL) {
                    // The view has RTL layout
                    currentFragment?.webView?.apply {
                        progression = 0.0
                        setCurrentItem(0, false)
                    }
                } else {
                    // The view has LTR layout
                    currentFragment?.webView?.apply {
                        progression = 1.0
                        setCurrentItem(numPages - 1, false)
                    }
                }
            }
        }
        return true
    }

    private val r2PagerAdapter: R2PagerAdapter
        get() = resourcePager.adapter as R2PagerAdapter

    private val currentFragment: R2EpubPageFragment? get() =
        r2PagerAdapter.mFragments.get(r2PagerAdapter.getItemId(resourcePager.currentItem)) as? R2EpubPageFragment

    override fun onTap(point: PointF): Boolean {
        (this.listener as Navigator.VisualListener).onTap(point)
        return super.onTap(point)
    }

    override val readingProgression: ReadingProgression
        get() = publication.contentLayout.readingProgression

    override fun goLeft(animated: Boolean, completion: () -> Unit): Boolean {
        TODO("Not yet implemented")
    }

    override fun goRight(animated: Boolean, completion: () -> Unit): Boolean {
        TODO("Not yet implemented")
    }

    override val currentLocator: LiveData<Locator?> get() = _currentLocator
    private val _currentLocator = MutableLiveData<Locator?>(null)

    /**
     * While scrolling we receive a lot of new current locations, so we use a coroutine job to
     * debounce the notification.
     */
    private var debounceLocationNotificationJob: Job? = null

    private fun notifyCurrentLocation() {
        val navigator = this
        debounceLocationNotificationJob?.cancel()
        debounceLocationNotificationJob = launch {
            delay(100L)

            val resource = publication.readingOrder[resourcePager.currentItem]
            val progression = currentFragment?.webView?.progression ?: 0.0
            val positions = publication.positionsByResource[resource.href]
                    ?: return@launch
            val positionIndex = ceil(progression * (positions.size - 1)).toInt()
            val locator = positions[positionIndex]
                    .copyWithLocations(progression = progression)

            if (locator == currentLocator.value) {
                return@launch
            }

            _currentLocator.postValue(locator)
            navigatorDelegate?.locationDidChange(navigator = navigator, locator = locator)
        }
    }

//    override fun currentSelection(callback: (Locator?) -> Unit) {
//        currentFragment?.webView?.getCurrentSelectionInfo {
//            val selection = JSONObject(it)
//            val resource = publication.readingOrder[resourcePager.currentItem]
//            val resourceHref = resource.href
//            val resourceType = resource.type ?: ""
//            val locations = Locator.Locations.fromJSON(selection.getJSONObject("locations"))
//            val text = Locator.Text.fromJSON(selection.getJSONObject("text"))
//
//            val locator = Locator(
//                    href = resourceHref,
//                    type = resourceType,
//                    locations = locations,
//                    text = text
//            )
//            callback(locator)
//        }
//    }
//
//    override fun showHighlight(highlight: Highlight) {
//        currentFragment?.webView?.run {
//            val colorJson = JSONObject().apply {
//                put("red", Color.red(highlight.color))
//                put("green", Color.green(highlight.color))
//                put("blue", Color.blue(highlight.color))
//            }
//            createHighlight(highlight.locator.toJSON().toString(), colorJson.toString()) {
//                if (highlight.annotationMarkStyle.isNullOrEmpty().not())
//                    createAnnotation(highlight.id)
//            }
//        }
//    }
//
//    override fun showHighlights(highlights: Array<Highlight>) {
//        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
//    }
//
//    override fun hideHighlightWithID(id: String) {
//        currentFragment?.webView?.destroyHighlight(id)
//        currentFragment?.webView?.destroyHighlight(id.replace("HIGHLIGHT", "ANNOTATION"))
//    }
//
//    override fun hideAllHighlights() {
//        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
//    }
//
//    override fun rectangleForHighlightWithID(id: String, callback: (Rect?) -> Unit) {
//        currentFragment?.webView?.rectangleForHighlightWithID(id) {
//            val rect = JSONObject(it).run {
//                try {
//                    val display = currentActivity.windowManager.defaultDisplay
//                    val metrics = DisplayMetrics()
//                    display.getMetrics(metrics)
//                    val left = getDouble("left")
//                    val width = getDouble("width")
//                    val top = getDouble("top") * metrics.density
//                    val height = getDouble("height") * metrics.density
//                    Rect(left.toInt(), top.toInt(), width.toInt() + left.toInt(), top.toInt() + height.toInt())
//                } catch (e: JSONException) {
//                    null
//                }
//            }
//            callback(rect)
//        }
//    }
//
//    override fun rectangleForHighlightAnnotationMarkWithID(id: String): Rect? {
//        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
//    }
//
//    override fun registerHighlightAnnotationMarkStyle(name: String, css: String) {
//        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
//    }
//
//    override fun highlightActivated(id: String) {
//    }
//
//    override fun highlightAnnotationMarkActivated(id: String) {
//    }
//
//    fun createHighlight(color: Int, callback: (Highlight) -> Unit) {
//        currentSelection { locator ->
//            val colorJson = JSONObject().apply {
//                put("red", Color.red(color))
//                put("green", Color.green(color))
//                put("blue", Color.blue(color))
//            }
//
//            currentFragment?.webView?.createHighlight(locator?.toJSON().toString(), colorJson.toString()) {
//                val json = JSONObject(it)
//                val id = json.getString("id")
//                callback(
//                        Highlight(
//                                id,
//                                locator!!,
//                                color,
//                                Style.highlight
//                        )
//                )
//            }
//        }
//    }
}