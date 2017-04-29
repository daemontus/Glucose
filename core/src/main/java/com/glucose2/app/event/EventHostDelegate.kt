package com.glucose2.app.event

import rx.Observable
import rx.Subscription
import rx.subjects.PublishSubject
import rx.subscriptions.CompositeSubscription
import java.util.*

internal class EventHostDelegate : EventHost {

    // Subjects responsible for handling local event processing
    private val actions = PublishSubject.create<Action>()
    private val events = PublishSubject.create<Event>()

    // Lists that store currently consumed classed.
    // Thread safety: Lists should be accessed only from the EventScheduler!
    // We use lists instead of sets because they can handle duplicates safely.
    private val consumedEvents = ArrayList<Class<*>>()
    private val consumedActions = ArrayList<Class<*>>()

    // Observable of events that are not consumed by this EventHost.
    // Parent EventHost should connect to this stream.
    private val eventsBridge: Observable<Event>
            = events.observeOn(EventScheduler)
            .filter { event -> consumedEvents.all { !it.isInstance(event) } }

    // Observable of actions that are not consumed by this EventHost.
    // Child EventHosts should connect to this stream.
    private val actionBridge: Observable<Action>
            = actions.observeOn(EventScheduler)
            .filter { action -> consumedActions.all { !it.isInstance(action) } }


    override fun <T : Event> observeEvent(type: Class<T>): Observable<T>
            = events.observeOn(EventScheduler)
            .filter { event -> type.isInstance(event) }
            .cast(type)

    override fun <T : Event> consumeEvent(type: Class<T>): Observable<T>
            = observeEvent(type).observeOn(EventScheduler)
            .doOnSubscribe { consumedEvents.add(type) }
            .doOnUnsubscribe { consumedEvents.remove(type) }

    override fun <T : Action> observeAction(type: Class<T>): Observable<T>
            = actions.observeOn(EventScheduler)
            .filter { action -> type.isInstance(action) }
            .cast(type)

    override fun <T : Action> consumeAction(type: Class<T>): Observable<T>
            = observeAction(type).observeOn(EventScheduler)
            .doOnSubscribe { consumedActions.add(type) }
            .doOnUnsubscribe { consumedActions.remove(type) }

    override fun emitEvent(event: Event) {
        this.events.onNext(event)
    }

    override fun emitAction(action: Action) {
        this.actions.onNext(action)
    }

    internal fun onAttach(parent: EventHostDelegate): Subscription
            = CompositeSubscription().apply {
                // while subscribed, pass all events from bridge to the parent presenter
                add(eventsBridge.subscribe(parent.events))
                // while subscribed, receive all actions from bridge from the parent presenter
                add(parent.actionBridge.subscribe(actions))
            }

}