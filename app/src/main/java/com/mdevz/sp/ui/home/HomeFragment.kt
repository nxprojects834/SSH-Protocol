package com.mdevz.sp.ui.home

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mdevz.sp.databinding.FragmentHomeBinding
import com.mdevz.sp.service.TunnelRuntime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    fun importProfileUri(
        value: String
    ): Boolean =
        controller?.importProfileUri(
            value
        ) ?: false

    interface Host {
        fun requestTunnelStart(
            profileId: String
        )

        fun requestTunnelStop()
    }

    private var _binding:
        FragmentHomeBinding? = null

    private val binding:
        FragmentHomeBinding
        get() = requireNotNull(_binding)

    private var controller:
        HomeController? = null

    private var connectivityManager:
        ConnectivityManager? = null

    private var networkCallback:
        ConnectivityManager.NetworkCallback? = null

    private val physicalNetworks =
        mutableMapOf<Network, PhysicalNetworkAddress>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding =
            FragmentHomeBinding.inflate(
                inflater,
                container,
                false
            )

        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(
            view,
            savedInstanceState
        )

        val host =
            requireActivity() as Host

        val runtime =
            TunnelRuntime.get(
                requireContext()
            )

        val homeController =
            HomeController(
                context = requireContext(),
                binding = binding,
                runtime = runtime,
                onConnectRequested = {
                    profileId ->
                    host.requestTunnelStart(
                        profileId
                    )
                },
                onStopRequested = {
                    host.requestTunnelStop()
                }
            )

        controller =
            homeController

        homeController.setup(
            preferredId =
                DEFAULT_PROFILE_ID
        )

        registerNetworkObserver(
            homeController
        )

        viewLifecycleOwner
            .lifecycleScope
            .launch {
                viewLifecycleOwner
                    .repeatOnLifecycle(
                        Lifecycle.State.STARTED
                    ) {
                        launch {
                            runtime.engine.state
                                .collect {
                                    homeController
                                        .renderState(it)
                                }
                        }

                        launch {
                            runtime.engine.traffic
                                .collect {
                                    homeController
                                        .renderTraffic(it)
                                }
                        }

                        launch {
                            while (true) {
                                homeController
                                    .renderRuntimeTick(
                                        System.currentTimeMillis()
                                    )

                                delay(
                                    RUNTIME_TICK_MILLIS
                                )
                            }
                        }
                    }
            }
    }

    override fun onDestroyView() {
        unregisterNetworkObserver()

        controller = null
        _binding = null

        super.onDestroyView()
    }

    private fun registerNetworkObserver(
        homeController: HomeController
    ) {
        val manager =
            requireContext()
                .getSystemService(
                    Context.CONNECTIVITY_SERVICE
                ) as ConnectivityManager

        val callback =
            object :
                ConnectivityManager.NetworkCallback() {

                override fun onAvailable(
                    network: Network
                ) {
                    updatePhysicalNetwork(
                        manager = manager,
                        network = network,
                        homeController = homeController
                    )
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    updatePhysicalNetwork(
                        manager = manager,
                        network = network,
                        capabilities = networkCapabilities,
                        homeController = homeController
                    )
                }

                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: LinkProperties
                ) {
                    updatePhysicalNetwork(
                        manager = manager,
                        network = network,
                        linkProperties = linkProperties,
                        homeController = homeController
                    )
                }

                override fun onLost(
                    network: Network
                ) {
                    physicalNetworks.remove(
                        network
                    )

                    publishBestPhysicalNetwork(
                        homeController
                    )
                }
            }

        connectivityManager =
            manager

        networkCallback =
            callback

        val request =
            NetworkRequest.Builder()
                .addCapability(
                    NetworkCapabilities
                        .NET_CAPABILITY_INTERNET
                )
                .build()

        manager.registerNetworkCallback(
            request,
            callback
        )

        val activeNetwork =
            manager.activeNetwork

        if (activeNetwork != null) {
            updatePhysicalNetwork(
                manager = manager,
                network = activeNetwork,
                homeController = homeController
            )
        } else {
            homeController.renderDeviceNetwork(
                address = null,
                networkType = null
            )
        }
    }

    private fun updatePhysicalNetwork(
        manager: ConnectivityManager,
        network: Network,
        capabilities: NetworkCapabilities? = null,
        linkProperties: LinkProperties? = null,
        homeController: HomeController
    ) {
        val resolvedCapabilities =
            capabilities
                ?: manager.getNetworkCapabilities(
                    network
                )

        if (resolvedCapabilities == null) {
            physicalNetworks.remove(
                network
            )

            publishBestPhysicalNetwork(
                homeController
            )

            return
        }

        if (
            resolvedCapabilities.hasTransport(
                NetworkCapabilities.TRANSPORT_VPN
            )
        ) {
            physicalNetworks.remove(
                network
            )

            publishBestPhysicalNetwork(
                homeController
            )

            return
        }

        if (
            !resolvedCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET
            )
        ) {
            physicalNetworks.remove(
                network
            )

            publishBestPhysicalNetwork(
                homeController
            )

            return
        }

        val resolvedLinkProperties =
            linkProperties
                ?: manager.getLinkProperties(
                    network
                )

        if (resolvedLinkProperties == null) {
            physicalNetworks.remove(
                network
            )

            publishBestPhysicalNetwork(
                homeController
            )

            return
        }

        val address =
            findIpv4Address(
                resolvedLinkProperties
            )

        if (address == null) {
            physicalNetworks.remove(
                network
            )

            publishBestPhysicalNetwork(
                homeController
            )

            return
        }

        val priority =
            when {
                resolvedCapabilities.hasTransport(
                    NetworkCapabilities.TRANSPORT_WIFI
                ) ->
                    NETWORK_PRIORITY_WIFI

                resolvedCapabilities.hasTransport(
                    NetworkCapabilities.TRANSPORT_CELLULAR
                ) ->
                    NETWORK_PRIORITY_CELLULAR

                resolvedCapabilities.hasTransport(
                    NetworkCapabilities.TRANSPORT_ETHERNET
                ) ->
                    NETWORK_PRIORITY_ETHERNET

                else ->
                    NETWORK_PRIORITY_OTHER
            }

        val networkType =
            when (priority) {
                NETWORK_PRIORITY_WIFI ->
                    homeController
                        .networkTypeWifi()

                NETWORK_PRIORITY_CELLULAR ->
                    homeController
                        .networkTypeCellular()

                NETWORK_PRIORITY_ETHERNET ->
                    homeController
                        .networkTypeEthernet()

                else ->
                    homeController
                        .networkTypeOther()
            }

        physicalNetworks[network] =
            PhysicalNetworkAddress(
                priority = priority,
                address = address,
                networkType = networkType
            )

        publishBestPhysicalNetwork(
            homeController
        )
    }

    private fun publishBestPhysicalNetwork(
        homeController: HomeController
    ) {
        val candidate =
            physicalNetworks
                .values
                .minByOrNull {
                    it.priority
                }

        view?.post {
            if (
                _binding == null
                || controller !== homeController
            ) {
                return@post
            }

            homeController
                .renderDeviceNetwork(
                    address =
                        candidate?.address,
                    networkType =
                        candidate?.networkType
                )
        }
    }

    private fun findIpv4Address(
        linkProperties: LinkProperties
    ): String? =
        linkProperties
            .linkAddresses
            .asSequence()
            .map {
                it.address
            }
            .firstOrNull {
                address ->
                address.address.size == IPV4_ADDRESS_SIZE
                    && !address.isLoopbackAddress
                    && !address.isLinkLocalAddress
                    && !address.isAnyLocalAddress
                    && !address.isMulticastAddress
            }
            ?.hostAddress

    private fun unregisterNetworkObserver() {
        val manager =
            connectivityManager

        val callback =
            networkCallback

        if (
            manager != null
            && callback != null
        ) {
            runCatching {
                manager.unregisterNetworkCallback(
                    callback
                )
            }
        }

        physicalNetworks.clear()

        networkCallback =
            null

        connectivityManager =
            null
    }

    private data class PhysicalNetworkAddress(
        val priority: Int,
        val address: String,
        val networkType: String
    )

    private companion object {
        const val DEFAULT_PROFILE_ID =
            "default"

        const val RUNTIME_TICK_MILLIS =
            1_000L

        const val IPV4_ADDRESS_SIZE =
            4

        const val NETWORK_PRIORITY_WIFI =
            0

        const val NETWORK_PRIORITY_CELLULAR =
            1

        const val NETWORK_PRIORITY_ETHERNET =
            2

        const val NETWORK_PRIORITY_OTHER =
            3
    }
}