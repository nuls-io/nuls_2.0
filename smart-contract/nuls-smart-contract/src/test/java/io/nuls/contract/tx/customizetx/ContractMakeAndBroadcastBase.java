/**
 * MIT License
 * <p>
 * Copyright (c) 2017-2018 nuls.io
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.nuls.contract.tx.customizetx;

import io.nuls.base.basic.AddressTool;
import io.nuls.base.data.NulsDigestData;
import io.nuls.base.data.Transaction;
import io.nuls.contract.constant.ContractErrorCode;
import io.nuls.contract.enums.LedgerUnConfirmedTxStatus;
import io.nuls.contract.helper.ContractHelper;
import io.nuls.contract.helper.ContractTxHelper;
import io.nuls.contract.manager.ChainManager;
import io.nuls.contract.model.tx.CallContractTransaction;
import io.nuls.contract.model.tx.CreateContractTransaction;
import io.nuls.contract.model.tx.DeleteContractTransaction;
import io.nuls.contract.model.txdata.ContractData;
import io.nuls.contract.rpc.call.AccountCall;
import io.nuls.contract.rpc.call.LedgerCall;
import io.nuls.contract.rpc.call.TransactionCall;
import io.nuls.contract.tx.base.BaseQuery;
import io.nuls.contract.util.Log;
import io.nuls.contract.util.MapUtil;
import io.nuls.rpc.util.RPCUtil;
import io.nuls.tools.basic.Result;
import io.nuls.tools.exception.NulsException;
import org.junit.Before;

import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import static io.nuls.contract.constant.ContractConstant.*;
import static io.nuls.contract.constant.ContractErrorCode.FAILED;
import static io.nuls.contract.util.ContractUtil.getFailed;
import static io.nuls.contract.util.ContractUtil.getSuccess;

/**
 * @author: PierreLuo
 * @date: 2019-03-29
 */
public class ContractMakeAndBroadcastBase extends BaseQuery {

    private ContractTxHelper contractTxHelper;

    @Before
    public void init() throws NoSuchFieldException, IllegalAccessException {
        ContractHelper contractHelper = new ContractHelper();
        ChainManager chainManager = new ChainManager();
        chainManager.getChainMap().put(chainId, chain);
        Field field1 = ContractHelper.class.getDeclaredField("chainManager");
        field1.setAccessible(true);
        field1.set(contractHelper, chainManager);

        contractTxHelper = new ContractTxHelper();
        Field field = ContractTxHelper.class.getDeclaredField("contractHelper");
        field.setAccessible(true);
        field.set(contractTxHelper, contractHelper);

        Log.info("bean init.");
    }


    protected Result<CreateContractTransaction> makeCreateTx(int chainId, String sender, Long gasLimit, Long price,
                                                           byte[] contractCode, String[][] args,
                                                           String password, String remark) {
        try {
            Result accountResult = AccountCall.validationPassword(chainId, sender, password);
            if (accountResult.isFailed()) {
                return accountResult;
            }
            // 生成一个地址作为智能合约地址
            String contractAddress = AccountCall.createContractAddress(chainId);
            byte[] contractAddressBytes = AddressTool.getAddress(contractAddress);
            byte[] senderBytes = AddressTool.getAddress(sender);
            return contractTxHelper.newCreateTx(chainId, sender, senderBytes, contractAddressBytes, gasLimit, price, contractCode, args, remark);
        } catch (NulsException e) {
            Log.error(e);
            return Result.getFailed(e.getErrorCode() == null ? FAILED : e.getErrorCode());
        }
    }

    protected Result broadcastCreateTx(CreateContractTransaction tx) {
        try {
            ContractData contractData = tx.getTxDataObj();
            byte[] contractAddressBytes = contractData.getContractAddress();
            byte[] senderBytes = contractData.getSender();
            Result result = this.broadcastTx(chainId, AddressTool.getStringAddressByBytes(senderBytes), password, tx);
            if(result.isFailed()) {
                return result;
            }
            Map<String, String> resultMap = MapUtil.createHashMap(2);
            String txHash = tx.getHash().getDigestHex();
            String contractAddressStr = AddressTool.getStringAddressByBytes(contractAddressBytes);
            resultMap.put("txHash", txHash);
            resultMap.put("contractAddress", contractAddressStr);
            return getSuccess().setData(resultMap);
        } catch (NulsException e) {
            Log.error(e);
            return Result.getFailed(e.getErrorCode() == null ? FAILED : e.getErrorCode());
        }
    }


    protected Result<CallContractTransaction> makeCallTx(int chainId, String sender, BigInteger value, Long gasLimit, Long price, String contractAddress,
                                                      String methodName, String methodDesc, String[][] args,
                                                      String password, String remark) {
        if (value == null) {
            value = BigInteger.ZERO;
        }
        Result accountResult = AccountCall.validationPassword(chainId, sender, password);
        if (accountResult.isFailed()) {
            return accountResult;
        }
        byte[] contractAddressBytes = AddressTool.getAddress(contractAddress);
        byte[] senderBytes = AddressTool.getAddress(sender);
        return contractTxHelper.newCallTx(chainId, sender, senderBytes, value, gasLimit, price, contractAddressBytes, methodName, methodDesc, args, remark);
    }

    protected Result broadcastCallTx(CallContractTransaction tx) {
        try {
            ContractData contractData = tx.getTxDataObj();
            byte[] senderBytes = contractData.getSender();
            Result result = this.broadcastTx(chainId, AddressTool.getStringAddressByBytes(senderBytes), password, tx);
            if(result.isFailed()) {
                return result;
            }
            Map<String, Object> resultMap = new HashMap<>(2);
            resultMap.put("txHash", tx.getHash().getDigestHex());
            return getSuccess().setData(resultMap);
        } catch (NulsException e) {
            Log.error(e);
            return Result.getFailed(e.getErrorCode() == null ? FAILED : e.getErrorCode());
        }
    }


    protected Result<DeleteContractTransaction> makeDeleteTx(int chainId, String sender, String contractAddress, String password, String remark) {
        Result accountResult = AccountCall.validationPassword(chainId, sender, password);
        if (accountResult.isFailed()) {
            return accountResult;
        }
        byte[] senderBytes = AddressTool.getAddress(sender);
        byte[] contractAddressBytes = AddressTool.getAddress(contractAddress);
        return contractTxHelper.newDeleteTx(chainId, sender, senderBytes, contractAddressBytes, remark);
    }

    protected Result broadcastDeleteTx(DeleteContractTransaction tx) {
        try {
            ContractData contractData = tx.getTxDataObj();
            byte[] senderBytes = contractData.getSender();
            Result result = this.broadcastTx(chainId, AddressTool.getStringAddressByBytes(senderBytes), password, tx);
            if(result.isFailed()) {
                return result;
            }
            Map<String, Object> resultMap = new HashMap<>(2);
            resultMap.put("txHash", tx.getHash().getDigestHex());
            return getSuccess().setData(resultMap);
        } catch (NulsException e) {
            Log.error(e);
            return Result.getFailed(e.getErrorCode() == null ? FAILED : e.getErrorCode());
        }
    }

    private Result broadcastTx(int chainId, String sender, String password, Transaction tx) {
        try {
            // 生成交易hash
            tx.setHash(NulsDigestData.calcDigestData(tx.serializeForHash()));
            // 生成签名
            AccountCall.transactionSignature(chainId, sender, password, tx);
            String txData = RPCUtil.encode(tx.serialize());

            Result validResult = this.validTx(chainId, tx);
            if(validResult.isFailed()) {
                return validResult;
            }
            // 通知账本
            int commitStatus = LedgerCall.commitUnconfirmedTx(chainId, txData);
            if(commitStatus != LedgerUnConfirmedTxStatus.SUCCESS.status()) {
                return getFailed().setMsg(LedgerUnConfirmedTxStatus.getStatus(commitStatus).name());
            }
            // 广播交易
            boolean broadcast = TransactionCall.newTx(chainId, txData);
            if (!broadcast) {
                // 广播失败，回滚账本的未确认交易
                LedgerCall.rollBackUnconfirmTx(chainId, txData);
                return getFailed();
            }
            return getSuccess();
        } catch (NulsException e) {
            Log.error(e);
            return Result.getFailed(e.getErrorCode() == null ? FAILED : e.getErrorCode());
        } catch (IOException e) {
            Log.error(e);
            Result result = Result.getFailed(ContractErrorCode.CONTRACT_TX_CREATE_ERROR);
            result.setMsg(e.getMessage());
            return result;
        }
    }

    private Result validTx(int chainId, Transaction tx) {
        try {
            Result result;
            switch (tx.getType()) {
                case TX_TYPE_CREATE_CONTRACT:
                    result = validCreateTx(chainId, (CreateContractTransaction) tx);
                    break;
                case TX_TYPE_CALL_CONTRACT:
                    result = validCallTx(chainId, (CallContractTransaction) tx);
                    break;
                case TX_TYPE_DELETE_CONTRACT:
                    result = validDeleteTx(chainId, (DeleteContractTransaction) tx);
                    break;
                default:
                    result = getSuccess();
                    break;
            }
            return result;
        } catch (Exception e) {
            return getFailed();
        }
    }

    protected Result validCreateTx(int chainId, CreateContractTransaction tx) {
        return getSuccess();
    }
    protected Result validCallTx(int chainId, CallContractTransaction tx) {
        return getSuccess();
    }
    protected Result validDeleteTx(int chainId, DeleteContractTransaction tx) {
        return getSuccess();
    }

}