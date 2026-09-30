#include "microgas_sender.h"
#include "dji_low_speed_data_channel.h"
#include <stdio.h>
#include <assert.h>
T_DjiReturnCode DjiLowSpeedDataChannel_SendData(E_DjiChannelAddress address, const uint8_t *data, uint8_t len) {
    assert(address == DJI_CHANNEL_ADDRESS_MASTER_RC_APP);
    assert(len == 32 || len == 64);
    for (unsigned i = 0; i < len; ++i) printf("%02x", data[i]);
    puts("");
    return DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS;
}
int main(void) {
    assert(MicroGas_SendCo2(42, 7, 123456, 650.5f) == DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS);
    MicroGasMeasurement sample = {650.5f, 24.5f, 60.25f, 5.1f, 8.3f, 10.5f, 12.75f, 100.f, 20.f};
    assert(MicroGas_SendMeasurement(42, 7, 123456, &sample) == DJI_ERROR_SYSTEM_MODULE_CODE_SUCCESS);
    assert(MicroGas_SendMeasurement(42, 7, 123456, NULL) == DJI_ERROR_SYSTEM_MODULE_CODE_INVALID_PARAMETER);
    sample.co2_ppm = -1;
    assert(MicroGas_SendMeasurement(42, 7, 123456, &sample) == DJI_ERROR_SYSTEM_MODULE_CODE_INVALID_PARAMETER);
    return 0;
}
