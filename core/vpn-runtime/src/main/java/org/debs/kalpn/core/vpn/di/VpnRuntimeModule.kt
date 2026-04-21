package org.debs.kalpn.core.vpn.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.debs.kalpn.core.vpn.controller.AndroidVpnConnectionController
import org.debs.kalpn.core.vpn.controller.VpnConnectionController
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
