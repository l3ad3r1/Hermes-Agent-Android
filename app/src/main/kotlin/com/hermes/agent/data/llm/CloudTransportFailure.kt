package com.hermes.agent.data.llm

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLException

internal fun Throwable.isTransientTransportFailure(): Boolean =
    generateSequence(this) { it.cause }.any { it is IOException }

internal fun IOException.toCloudFailureMessage(): String {
    val causeChain = generateSequence<Throwable>(this) { it.cause }.toList()
    return when {
        causeChain.any { it is UnknownServiceException && it.message.orEmpty().contains("CLEARTEXT", true) } ->
            "Android blocked an insecure cloud connection. Use an HTTPS API URL."
        causeChain.any { it is UnknownHostException } ->
            "Couldn't resolve the cloud model host. Check the API URL, DNS, and internet connection."
        causeChain.any { it is ConnectException } ->
            "The cloud model refused the connection. Check the API URL, port, and provider status."
        causeChain.any { it is SocketTimeoutException } ->
            "The cloud model timed out. Check the provider status and try again."
        causeChain.any { it is SSLException } ->
            "The secure cloud connection failed. Check the HTTPS URL, certificate, and device date."
        causeChain.any { it is SocketException } ->
            "The cloud connection was interrupted. Check the network or provider status and try again."
        else ->
            "Couldn't reach the cloud model. Check the API URL, internet connection, and provider status."
    }
}