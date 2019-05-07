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

package io.nuls.transaction.threadpool;

import io.nuls.base.data.Transaction;
import io.nuls.core.core.annotation.Autowired;
import io.nuls.core.core.annotation.Component;
import io.nuls.core.exception.NulsException;
import io.nuls.core.thread.ThreadUtils;
import io.nuls.core.thread.commom.NulsThreadFactory;
import io.nuls.transaction.cache.PackablePool;
import io.nuls.transaction.constant.TxConstant;
import io.nuls.transaction.constant.TxErrorCode;
import io.nuls.transaction.model.bo.Chain;
import io.nuls.transaction.model.po.TransactionNetPO;
import io.nuls.transaction.rpc.call.LedgerCall;
import io.nuls.transaction.rpc.call.NetworkCall;
import io.nuls.transaction.service.TxService;
import io.nuls.transaction.storage.UnconfirmedTxStorageService;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * @author: Charlie
 * @date: 2019/5/5
 */
@Component
public class NetTxProcess {


//    private PackablePool packablePool = SpringLiteContext.getBean(PackablePool.class);
//    private TxService txService = SpringLiteContext.getBean(TxService.class);
//    private UnconfirmedTxStorageService unconfirmedTxStorageService = SpringLiteContext.getBean(UnconfirmedTxStorageService.class);

    @Autowired
    private PackablePool packablePool;
    @Autowired
    private TxService txService;
    @Autowired
    private UnconfirmedTxStorageService unconfirmedTxStorageService;

    private ExecutorService verifyExecutor = ThreadUtils.createThreadPool(Runtime.getRuntime().availableProcessors(), Integer.MAX_VALUE, new NulsThreadFactory(TxConstant.THREAD_VERIFIY_NEW_TX));

    public static final int PROCESS_NUMBER_ONCE = 2000;

    public static List<TransactionNetPO> txNetList = new ArrayList<>(PROCESS_NUMBER_ONCE);
    /**
     * 优化待测
     * @throws RuntimeException
     */
    public void process(Chain chain) throws RuntimeException {
      /*  List<TransactionNetPO> txNetList = new ArrayList<>(processNumberonce);
        chain.getUnverifiedQueue().drainTo(txNetList, processNumberonce);
        if(txNetList.isEmpty()){
            return;
        }*/
        if(txNetList.isEmpty()){
          return;
        }
        Map<String, TransactionNetPO> txNetMap = null;
        List<Transaction> txList = null;
        List<Future<String>> futures = null;
        try {
            txNetMap = new HashMap<>(PROCESS_NUMBER_ONCE);
            txList = new LinkedList<>();
            futures = new ArrayList<>();
            for(TransactionNetPO txNet : txNetList){
                Transaction tx = txNet.getTx();
                //多线程处理单个交易
                Future<String> res = verifyExecutor.submit(new Callable<String>() {
                    @Override
                    public String call() {
                        /**if(txService.isTxExists(chain, tx.getHash())){
                         return false;
                         }*/
                        if (!txService.verify(chain, tx).getResult()) {
                            chain.getLoggerMap().get(TxConstant.LOG_NEW_TX_PROCESS).error("Net new tx verify fail.....hash:{}", tx.getHash().getDigestHex());
                            return tx.getHash().getDigestHex();
                        }
                        return null;
                    }
                });
                futures.add(res);
                txList.add(tx);
                txNetMap.put(tx.getHash().getDigestHex(), txNet);
            }
        } catch (RuntimeException e) {
            e.printStackTrace();
        } finally {
            txNetList.clear();
        }


        List<String> txFailList = new LinkedList<>();
        //多线程处理结果
        try {
            for (Future<String> future : futures) {
                if (null != future.get()) {
                    txFailList.add(future.get());
                }
            }
        } catch (InterruptedException e) {
            chain.getLoggerMap().get(TxConstant.LOG_NEW_TX_PROCESS).error(e);
            return;
        } catch (ExecutionException e) {
            chain.getLoggerMap().get(TxConstant.LOG_NEW_TX_PROCESS).error(e);
            return;
        }
        //有验证不通过的，则过滤掉
        if(!txFailList.isEmpty()) {
            Iterator<Transaction> it = txList.iterator();
            while (it.hasNext()) {
                Transaction tx = it.next();
                for(String hash : txFailList){
                    if(hash.equals(tx.getHash().getDigestHex())){
                        it.remove();
                    }
                }
            }
        }

        if(txList.isEmpty()){
            return;
        }
        try {
            verifyCoinData(chain, txList, txNetMap);
            for(Transaction tx : txList) {
                if (chain.getPackaging().get()) {
                    //当节点是出块节点时, 才将交易放入待打包队列
                    packablePool.add(chain, tx);
//                    chain.getLoggerMap().get(TxConstant.LOG_NEW_TX_PROCESS).debug("交易[加入待打包队列].....");
                }
                //保存到rocksdb
                unconfirmedTxStorageService.putTx(chain.getChainId(), tx);
                //转发交易hash
                TransactionNetPO txNetPo = txNetMap.get(tx.getHash().getDigestHex());
                NetworkCall.forwardTxHash(chain.getChainId(), tx.getHash(), txNetPo.getExcludeNode());
            }
        } catch (NulsException e) {
            chain.getLoggerMap().get(TxConstant.LOG_NEW_TX_PROCESS).error("Net new tx process exception, -code:{}",e.getErrorCode().getCode());
        }


    }

    public void verifyCoinData(Chain chain, List<Transaction> txList, Map<String, TransactionNetPO> txNetMap) throws NulsException{
        try {
            Map verifyCoinDataResult = LedgerCall.commitBatchUnconfirmedTxs(chain, txList);
            List<String> failHashs = (List<String>)verifyCoinDataResult.get("fail");
            List<String> orphanHashs = (List<String>)verifyCoinDataResult.get("orphan");
            Iterator<Transaction> it = txList.iterator();
            while (it.hasNext()) {
                Transaction tx = it.next();
                //去除账本验证失败的交易
                for(String hash : failHashs){
                    if(hash.equals(tx.getHash().getDigestHex())){
                        it.remove();
                        continue;
                    }
                }
                //去除孤儿交易, 同时把孤儿交易放入孤儿池
                for(String hash : orphanHashs){
                    if(hash.equals(tx.getHash().getDigestHex())){
                        //孤儿交易
                        List<TransactionNetPO> chainOrphan = chain.getOrphanList();
                        synchronized (chainOrphan){
                            chainOrphan.add(txNetMap.get(hash));
                        }
                        chain.getLoggerMap().get(TxConstant.LOG_NEW_TX_PROCESS).debug("Net new tx coinData orphan, - type:{}, - txhash:{}",
                                tx.getType(), tx.getHash().getDigestHex());
                        it.remove();
                        continue;
                    }
                }
            }
        }catch (RuntimeException e) {
            throw new NulsException(TxErrorCode.SYS_UNKOWN_EXCEPTION);
        }
    }
}