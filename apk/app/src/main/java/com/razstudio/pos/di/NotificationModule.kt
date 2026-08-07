package com.razstudio.pos.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt module for the Payment Notification Listener feature.
 *
 * Most notification components are provided through constructor injection:
 * - [com.razstudio.pos.notification.NotificationParser] — @Singleton @Inject constructor
 * - [com.razstudio.pos.notification.PaymentMatcher] — @Singleton @Inject constructor
 * - [com.razstudio.pos.notification.ListenerPrefsStore] — @Singleton @Inject constructor
 * - [com.razstudio.pos.notification.PaymentAlertBroadcaster] — @Singleton @Inject constructor
 *
 * [com.razstudio.pos.notification.CapturedPaymentDao] is provided by [DatabaseModule] via
 * the AppDatabase accessor.
 *
 * [com.razstudio.pos.notification.PaymentNotificationListener] uses @AndroidEntryPoint
 * to receive injected dependencies (field injection via @Inject lateinit var).
 *
 * This module exists as an organizational marker and extension point for any future @Provides
 * or @Binds declarations the notification feature may need.
 */
@Module
@InstallIn(SingletonComponent::class)
object NotificationModule
