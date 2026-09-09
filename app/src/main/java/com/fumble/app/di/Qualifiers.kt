package com.fumble.app.di

import javax.inject.Qualifier

/** The dispatcher used for MediaStore cursors and Room writes. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher
