package com.glucose.app

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.support.annotation.IdRes
import android.support.annotation.LayoutRes
import android.util.SparseArray
import android.view.LayoutInflater
import android.view.View
import com.glucose.app.presenter.*
import rx.Observable
import rx.subjects.PublishSubject
import java.util.*

import com.glucose.app.presenter.Lifecycle.State.*

/**
 * Presenter is responsible for a single, possibly modular part of UI.
 * Presenter should be completely isolated in terms of state from it's parent
 * (therefore it doesn't even have a reference to it), however, it can access
 * global data by means of a [PresenterContext].
 *
 * Presenter is responsible for managing it's child Presenters and relationships
 * between them (see [PresenterGroup]).
 *
 * Presenter lifecycle is similar to that of an [Activity], but instead of the
 * onCreate -> onRestart loop, it has onAttach -> onDetach.
 *
 * Other lifecycle information:
 * - Presenter is always connected to it's view, however, this view is only connected to the
 * view hierarchy after [onAttach] (and before [onStart]).
 * - Similarly, presenter's view is detached from the hierarchy before [onDetach], but after [onStop].
 * - Presenter is identified by it's [id]. If the user does not override the ID getter, the
 * id is assumed to be given in the argument bundle.
 * - Similar to views, ID is used to identify presenters when saving/restoring state, so
 * a presenter without an ID won't have it's state saved and in case of an ID clash, the
 * behavior is undefined.
 * - The existence of a child will be saved only if the parent has an ID and the [PresenterLayout]
 * the child is in also has an ID.
 * - Presenter can use [canChangeConfiguration] to indicate if it wants to be recreated or if
 * it will handle a configuration change on it's own.
 * - In case of a configuration change, presenter's parent should save it's state and recreate if,
 * or notify it about the change (depending on the presenter)
 * - Default implementation for [onBackPressed] will traverse the presenter hierarchy and stop as
 * soon as one of the presenters returns true (hence only the first presenter will actually
 * do a step back).
 * - On the other hand, [onActivityResult] is delivered to all presenters in the hierarchy.
 * (This might be changed later on with a better mechanism as soon as the permissions are
 * also solved)
 *
 * Presenter can always be placed only in the [PresenterLayout] - this is mainly to simplify
 * the reuse (All them layout params).
 *
 * ### Presenter as [ActionHost]
 *
 * Each presenter can be used as an [ActionHost]. Internally, it uses [MainThreadActionHost]
 * so that each action is executed on main thread by default. Action execution is started after
 * the call to [onAttach] (to ensure that all state has been restored) and stopped
 * before the call to [onDetach] (to ensure state isn't changing any more). Actions posted
 * outside of this window are thrown away with an error.
 *
 * @see [ActionHost]
 * @see [LifecycleHost]
 * TODO: Handle onRequestPermissionResult.
 */
open class Presenter(
        context: PresenterContext, val view: View
) : LifecycleHost, ActionHost {

    /**
     * Create a Presenter with view initialized from layout resource.
     */
    constructor(context: PresenterContext, @LayoutRes layout: Int) : this(
            context, LayoutInflater.from(context.activity).inflate(layout, PresenterLayout(context.activity), false)
    )

    var arguments: Bundle = Bundle()
        private set
        get() {
            if (!this.isAttached)
                throw LifecycleException("Presenter is not attached. Cannot access arguments.")
            else return field
        }

    val ctx: PresenterContext = context
        get() {
            if (this.isDestroyed)
                throw LifecycleException("Accessing Context on a destroyed presenter.")
            return field
        }

    open val id: Int
        get() = if (this.isAttached) {
            arguments.getInt("id", View.NO_ID)
        } else View.NO_ID

    /******************** Methods driving the lifecycle of this Presenter. ************************/

    private inline fun assertLifecycleChange(
            from: Lifecycle.State, to: Lifecycle.State, transition: () -> Unit
    ) {
        if (state != from) throw IllegalStateException("Something is wrong with the lifecycle!")
        transition()
        if (state != to) throw IllegalStateException("Something is wrong with the lifecycle! Maybe forgot to call super?")
    }

    internal fun performAttach(arguments: Bundle) = assertLifecycleChange(ALIVE, ATTACHED) {
        onAttach(arguments)
        actionHost.startProcessingActions()
    }

    internal fun performStart() = assertLifecycleChange(ATTACHED, STARTED) { onStart() }

    internal fun performResume() = assertLifecycleChange(STARTED, RESUMED) { onResume() }

    internal fun performPause() = assertLifecycleChange(RESUMED, STARTED) { onPause() }

    internal fun performStop() = assertLifecycleChange(STARTED, ATTACHED) { onStop() }

    internal fun performDetach() = assertLifecycleChange(ATTACHED, ALIVE) {
        actionHost.stopProcessingActions()
        onDetach()
    }

    internal fun performDestroy() = assertLifecycleChange(ALIVE, DESTROYED) { onDestroy() }

    protected open fun onAttach(arguments: Bundle) {
        lifecycleLog("onAttach")
        this.arguments = arguments
        myState = ATTACHED
        onLifecycleEvent(Lifecycle.Event.ATTACH)
    }

    /**
     * @see Activity.onStart
     */
    protected open fun onStart() {
        lifecycleLog("onStart")
        myState = STARTED
        onLifecycleEvent(Lifecycle.Event.START)
    }

    /**
     * @see Activity.onResume
     */
    protected open fun onResume() {
        lifecycleLog("onResume")
        myState = RESUMED
        onLifecycleEvent(Lifecycle.Event.RESUME)
    }

    /**
     * @see Activity.onPause
     */
    protected open fun onPause() {
        onLifecycleEvent(Lifecycle.Event.PAUSE)
        myState = STARTED
        lifecycleLog("onPause")
    }

    /**
     * @see Activity.onStop
     */
    protected open fun onStop() {
        onLifecycleEvent(Lifecycle.Event.STOP)
        myState = ATTACHED
        lifecycleLog("onStop")
    }

    protected open fun onDetach() {
        onLifecycleEvent(Lifecycle.Event.DETACH)
        myState = ALIVE
        lifecycleLog("onDetach")
    }

    /**
     * @see Activity.onDestroy
     */
    protected open fun onDestroy() {
        onLifecycleEvent(Lifecycle.Event.DESTROY)
        myState = DESTROYED
        lifecycleLog("onDestroy")
    }

    open val canChangeConfiguration = true

    /**
     * Called when this presenter changes configuration.
     *
     * Won't be called if [canChangeConfiguration] is false - instead, presenter
     * will be recreated.
     *
     * @see canChangeConfiguration
     * @see Activity.onConfigurationChanged
     */
    open fun onConfigurationChanged(newConfig: Configuration) {
        ctx.factory.cleanUpBeforeConfigChange()
        if (!canChangeConfiguration) {
            throw IllegalStateException("$this cannot change configuration and should have been destroyed.")
        }
        lifecycleLog("onConfigurationChanged")
    }

    /**
     * Notification to all attached presenters that activity has returned a result.
     *
     * @see Activity.onActivityResult
     */
    open fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        lifecycleLog("onActivityResult $resultCode for request $requestCode")
    }

    /**
     * Notification that the Presenter should move to the previous state.
     * @return false if presenter can't go back, true if it successfully moved back.
     *
     * @see Activity.onBackPressed
     */
    open fun onBackPressed(): Boolean = false

    /**
     * Save state of this [Presenter] and all it's children into a container,
     * assuming they have an ID set.
     */
    open fun saveHierarchyState(container: SparseArray<Bundle>) {
        if (id != View.NO_ID) {
            val out = Bundle()
            onSaveInstanceState(out)
            container.put(id, out)
        }
    }

    /**
     * Save state of this [Presenter] into a given Bundle.
     */
    open fun onSaveInstanceState(out: Bundle) {
        out.putAll(arguments)   //make a copy of the current state
    }

    /**
     * Notification that system memory is running low.
     *
     * @see Activity.onTrimMemory
     */
    open fun onTrimMemory(level: Int) {}

    /******************** [LifecycleHost] implementation ******************************************/

    private val lifecycleEventSubject = PublishSubject.create<Lifecycle.Event>()
    override val lifecycleEvents: Observable<Lifecycle.Event> = lifecycleEventSubject

    private var myState: Lifecycle.State = Lifecycle.State.ALIVE
    override val state: Lifecycle.State
        get() = myState

    private val lifecycleCallbacks = ArrayList<Pair<Lifecycle.Event, () -> Unit>>()

    /**
     * @see [LifecycleHost.addEventCallback]
     */
    override fun addEventCallback(event: Lifecycle.Event, callback: () -> Unit) {
        lifecycleCallbacks.add(event to callback)
    }

    /**
     * @see [LifecycleHost.removeEventCallback]
     */
    override fun removeEventCallback(event: Lifecycle.Event, callback: () -> Unit): Boolean {
        return lifecycleCallbacks.remove(event to callback)
    }

    /**
     * Ensures callbacks and notifications regarding a Lifecycle event are dispatched.
     */
    private fun onLifecycleEvent(event: Lifecycle.Event) {
        val victims = lifecycleCallbacks.filter { it.first == event }
        lifecycleCallbacks.removeAll(victims)
        victims.forEach { it.second.invoke() }
        lifecycleEventSubject.onNext(event)
    }

    /******************** [ActionHost] implementation *********************************************/

    private val actionHost = MainThreadActionHost()

    /**
     * @see [ActionHost.post]
     */
    override fun <R> post(action: Observable<R>): Observable<R> = actionHost.post(action)

    // ============================ View related helper functions ==================================

    @Suppress("UNCHECKED_CAST")
    fun <V: View> findView(@IdRes viewId: Int): V = view.findViewById(viewId) as V

    @Suppress("UNCHECKED_CAST")
    fun <V: View> findOptionalView(@IdRes viewId: Int): V? = view.findViewById(viewId) as V?

}