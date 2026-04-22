package org.debs.mayday.core.vpn.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.debs.mayday.core.vpn.controller.AndroidVpnConnectionController
import org.debs.mayday.core.vpn.controller.VpnConnectionController
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class VpnRuntimeModule {

    @Binds
    @Singleton
    abstract fun bindVpnConnectionController(
        controller: AndroidVpnConnectionController,
    ): VpnConnectionController
}
