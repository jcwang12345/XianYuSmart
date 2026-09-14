-- XYM-PUB-001: repair only the isolated QA access-channel fixtures that were
-- once imported without SET NAMES utf8mb4. Hex literals keep this migration
-- independent from the client connection encoding. No production channel is
-- matched because QA_LOCAL + MANUAL_IMPORT + QA_* error codes are required.
UPDATE xianyu_account_access_channel
   SET last_error_message = CONVERT(0xE983A8E58886E5B9B3E58FB0E5AD97E6AEB5E69CAAE5908CE6ADA5 USING utf8mb4)
 WHERE channel_code = 'QA_LOCAL'
   AND source = 'MANUAL_IMPORT'
   AND last_error_code = 'QA_PARTIAL'
   AND HEX(last_error_message) = 'C3A9C692C2A8C3A5CB86E280A0C3A5C2B9C2B3C3A5C28FC2B0C3A5C2ADE28094C3A6C2AEC2B5C3A6C593C2AAC3A5C290C592C3A6C2ADC2A5';
