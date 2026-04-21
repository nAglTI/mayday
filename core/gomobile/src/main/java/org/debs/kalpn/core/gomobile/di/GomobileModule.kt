package org.debs.kalpn.core.gomobile.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.debs.kalpn.core.gomobile.bridge.AarBackedVpnCoreBridge
import org.debs.kalpn.core.gomobile.bridge.VpnCoreBridge
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class GomobileModule {

    @Binds
    @Singleton
    abstract fun bindVpnCoreBridge(
        bridge: AarBackedVpnCoreBridge,
    ): VpnCoreBridge
}
