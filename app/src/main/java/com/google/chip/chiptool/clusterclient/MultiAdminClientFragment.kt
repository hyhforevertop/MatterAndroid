package com.google.chip.chiptool.clusterclient

import APICommand
import CommandMessage
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import chip.devicecontroller.ChipDeviceController
import chip.devicecontroller.ClusterIDMapping.AdministratorCommissioning
import chip.devicecontroller.InvokeCallback
import chip.devicecontroller.OpenCommissioningCallback
import chip.devicecontroller.ReportCallback
import chip.devicecontroller.model.ChipAttributePath
import chip.devicecontroller.model.ChipEventPath
import chip.devicecontroller.model.InvokeElement
import chip.devicecontroller.model.NodeState
import com.google.chip.chiptool.ChipClient
import com.google.chip.chiptool.GenericChipDeviceListener
import com.google.chip.chiptool.MyWebSocketListener
import com.google.chip.chiptool.R
import com.google.chip.chiptool.WebSocketClient
import com.google.chip.chiptool.WebSocketResponseListener
import com.google.chip.chiptool.databinding.MultiAdminClientFragmentBinding
import com.google.chip.chiptool.util.toAny
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import matter.tlv.AnonymousTag
import matter.tlv.TlvReader
import matter.tlv.TlvWriter

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.ByteString
import java.util.UUID

class MultiAdminClientFragment : Fragment() {
  private val deviceController: ChipDeviceController
    get() = ChipClient.getDeviceController(requireContext())

  private lateinit var scope: CoroutineScope

  private lateinit var addressUpdateFragment: AddressUpdateFragment

  private var _binding: MultiAdminClientFragmentBinding? = null
  private val binding
    get() = _binding!!

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    _binding = MultiAdminClientFragmentBinding.inflate(inflater, container, false)
    scope = viewLifecycleOwner.lifecycleScope

    deviceController.setCompletionListener(ChipControllerCallback())

    addressUpdateFragment =
      childFragmentManager.findFragmentById(R.id.addressUpdateFragment) as AddressUpdateFragment

    binding.basicCommissioningMethodBtn.setOnClickListener {
      scope.launch { sendBasicCommissioningCommandClick() }
    }
    binding.enhancedCommissioningMethodBtn.setOnClickListener {
      scope.launch { sendEnhancedCommissioningCommandClick() }
    }
    binding.revokeBtn.setOnClickListener { scope.launch { sendRevokeCommandClick() } }
    binding.readWindowStatusBtn.setOnClickListener {
      scope.launch {
        readAdministratorCommissioningClusterAttributeClick(
          AdministratorCommissioning.Attribute.WindowStatus
        )
      }
    }
    binding.readAdminFabricIndexBtn.setOnClickListener {
      scope.launch {
        readAdministratorCommissioningClusterAttributeClick(
          AdministratorCommissioning.Attribute.AdminFabricIndex
        )
      }
    }
    binding.readAdminVendorIdBtn.setOnClickListener {
      scope.launch {
        readAdministratorCommissioningClusterAttributeClick(
          AdministratorCommissioning.Attribute.AdminVendorId
        )
      }
    }

    return binding.root
  }

  override fun onStart() {
    super.onStart()
    // TODO: use the discriminator and setupPinCode that was used to commission the device
    val testDiscriminator = "3840"
    val testSetupPinCode = 20202021L
    val testDuration = 180
    binding.discriminatorEd.setText(testDiscriminator)
    binding.setupPinCodeEd.setText(testSetupPinCode.toString())
    binding.timeoutEd.setText(testDuration.toString())
  }

  override fun onDestroyView() {
    super.onDestroyView()
    _binding = null
  }

  inner class ChipControllerCallback : GenericChipDeviceListener() {
    override fun onConnectDeviceComplete() {}

    override fun onCommissioningComplete(nodeId: Long, errorCode: Long) {
      Log.d(TAG, "onCommissioningComplete for nodeId $nodeId: $errorCode")
    }

    override fun onNotifyChipConnectionClosed() {
      Log.d(TAG, "onNotifyChipConnectionClosed")
    }

    override fun onCloseBleComplete() {
      Log.d(TAG, "onCloseBleComplete")
    }

    override fun onError(error: Throwable?) {
      Log.d(TAG, "onError: $error")
    }
  }

  private suspend fun sendBasicCommissioningCommandClick() {
    val testDuration = binding.timeoutEd.text.toString().toInt()
    val devicePtr =
      try {
        ChipClient.getConnectedDevicePointer(requireContext(), addressUpdateFragment.deviceId)
      } catch (e: IllegalStateException) {
        Log.d(TAG, "getConnectedDevicePointer exception", e)
        showMessage("Get DevicePointer fail!")
        return
      }
    deviceController.openPairingWindowCallback(
      devicePtr,
      testDuration,
      object : OpenCommissioningCallback {
        override fun onError(status: Int, deviceId: Long) {
          showMessage("OpenBasicCommissioning Fail! \nDevice ID : $deviceId\nErrorCode : $status")
        }

        override fun onSuccess(deviceId: Long, manualPairingCode: String?, qrCode: String?) {
          showMessage("OpenBasicCommissioning Success! \n Node ID: $deviceId")
        }
      }
    )
  }

  private suspend fun sendEnhancedCommissioningCommandClick() {
    val testDuration = binding.timeoutEd.text.toString().toInt()
    val testIteration = 1000
    var  nodeIdCurrent=0;
    val devicePointer =
      try {
        ChipClient.getConnectedDevicePointer(requireContext(), addressUpdateFragment.deviceId)
      } catch (e: IllegalStateException) {
        Log.d(TAG, "getConnectedDevicePointer exception", e)
        showMessage("Get DevicePointer fail!")
        return
      }

    var setupPinCode: Long? = null
    if (!binding.setupPinCodeEd.text.toString().isEmpty()) {
      setupPinCode = binding.setupPinCodeEd.text.toString().toULong().toLong()
    }
    deviceController.openPairingWindowWithPINCallback(
      devicePointer,
      testDuration,
      testIteration.toLong(),
      binding.discriminatorEd.text.toString().toInt(),
      setupPinCode,
      object : OpenCommissioningCallback {
        override fun onError(status: Int, deviceId: Long) {
          showMessage("OpenCommissioning Fail! \nDevice ID : $deviceId\nErrorCode : $status")
        }

        override fun onSuccess(deviceId: Long, manualPairingCode: String?, qrCode: String?) {


            Log.e("Manucode",manualPairingCode.toString())
          val webSocketClient = WebSocketClient("ws://192.168.118.177:5580/ws", MyWebSocketListener(object :
            WebSocketResponseListener {
            override fun onMessageReceived(message: String) {
              // Handle received message
              println("Message received: $message")

              val jsonObject = JsonParser().parse(message).asJsonObject
              if (jsonObject.has("result")) {
                val result = jsonObject.getAsJsonArray("result")
                result.forEach { element ->
                  val node = element.asJsonObject
                  if (node.has("node_id")) {
                    val nodeId = node.get("node_id").asInt
                    nodeIdCurrent=nodeId+1
                    showMessage("Node ID Current: $nodeIdCurrent")

                  }
                }
              }
            }

            override fun onBytesReceived(bytes: ByteString) {
              // Handle received bytes
              println("Bytes received: ${bytes.hex()}")
            }
          }))

          showMessage(
            "OpenCommissioning Success! \n Node ID: $deviceId\n\tManual : $manualPairingCode\n\tQRCode : $qrCode" +
                    "\n Node ID current: $nodeIdCurrent\n\t"

          )

          webSocketClient.connect()

          val jsonMessage = JsonObject().apply {
            addProperty("message_id", UUID.randomUUID().toString())
            addProperty("command", APICommand.COMMISSION_WITH_CODE.value)
            val args = JsonObject().apply {
              addProperty("code", manualPairingCode.toString())
            }
            add("args", args)
          }
          webSocketClient.sendMessage(jsonMessage.toString())

          val jsonMessage2 = JsonObject().apply {
            addProperty("message_id", UUID.randomUUID().toString())
            addProperty("command", APICommand.GET_NODES.value)
          }
          webSocketClient.sendMessage(jsonMessage2.toString())

          Thread.sleep(10000)
          webSocketClient.close()
        }
      }
    )
  }

  private suspend fun sendRevokeCommandClick() {
    val timedInvokeTimeout = 10000
    // TODO : Need to be implement poj-to-tlv
    val tlvWriter = TlvWriter()
    tlvWriter.startStructure(AnonymousTag)
    tlvWriter.endStructure()
    val invokeElement =
      InvokeElement.newInstance(
        ADMINISTRATOR_COMMISSIONING_CLUSTER_ENDPOINT_ID,
        AdministratorCommissioning.ID,
        AdministratorCommissioning.Command.RevokeCommissioning.id,
        tlvWriter.getEncoded(),
        null
      )

    val devicePointer =
      try {
        ChipClient.getConnectedDevicePointer(requireContext(), addressUpdateFragment.deviceId)
      } catch (e: IllegalStateException) {
        Log.d(TAG, "getConnectedDevicePointer exception", e)
        showMessage("Get DevicePointer fail!")
        return
      }
    deviceController.invoke(
      object : InvokeCallback {
        override fun onError(ex: Exception?) {
          showMessage("Revoke Commissioning  failure $ex")
          Log.e(TAG, "Revoke Commissioning  failure", ex)
        }

        override fun onResponse(invokeElement: InvokeElement?, successCode: Long) {
          Log.e(TAG, "onResponse : $invokeElement, Code : $successCode")
          showMessage("Revoke Commissioning success")
        }
      },
      devicePointer,
      invokeElement,
      timedInvokeTimeout,
      0
    )
  }

  private suspend fun readAdministratorCommissioningClusterAttributeClick(
    attribute: AdministratorCommissioning.Attribute
  ) {
    val endpointId = ADMINISTRATOR_COMMISSIONING_CLUSTER_ENDPOINT_ID
    val clusterId = AdministratorCommissioning.ID
    val attributeId = attribute.id
    val attributeName = attribute.name
    val attributePath = ChipAttributePath.newInstance(endpointId, clusterId, attributeId)

    val devicePointer =
      try {
        ChipClient.getConnectedDevicePointer(requireContext(), addressUpdateFragment.deviceId)
      } catch (e: IllegalStateException) {
        Log.d(TAG, "getConnectedDevicePointer exception", e)
        showMessage("Get DevicePointer fail!")
        return
      }

    deviceController.readAttributePath(
      object : ReportCallback {
        override fun onReport(nodeState: NodeState?) {
          val tlv =
            nodeState
              ?.getEndpointState(endpointId)
              ?.getClusterState(clusterId)
              ?.getAttributeState(attributeId)
              ?.tlv
          val value = tlv?.let { TlvReader(it).toAny() }
          Log.i(TAG, "read $attributeName: $value")
          showMessage("read $attributeName: $value")
        }

        override fun onError(
          attributePath: ChipAttributePath?,
          eventPath: ChipEventPath?,
          e: Exception
        ) {
          showMessage("read $attributeName - error : ${e?.message}")
        }
      },
      devicePointer,
      listOf(attributePath),
      0
    )
  }

  private fun showMessage(msg: String) {
    requireActivity().runOnUiThread { binding.multiAdminClusterCommandStatus.text = msg }
  }

  companion object {
    private const val TAG = "MultiAdminClientFragment"
    private const val ADMINISTRATOR_COMMISSIONING_CLUSTER_ENDPOINT_ID = 0

    fun newInstance(): MultiAdminClientFragment = MultiAdminClientFragment()
  }
}
