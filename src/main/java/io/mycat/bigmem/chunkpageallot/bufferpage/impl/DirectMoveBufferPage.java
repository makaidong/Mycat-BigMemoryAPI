package io.mycat.bigmem.chunkpageallot.bufferpage.impl;

import java.util.Set;
import java.util.TreeSet;

import io.mycat.bigmem.chunkpageallot.buffer.DirectMemAddressInf;
import io.mycat.bigmem.chunkpageallot.buffer.MycatBufferBase;
import io.mycat.bigmem.chunkpageallot.buffer.MycatMovableBufer;
import io.mycat.bigmem.chunkpageallot.bufferpage.BufferPageBase;
import io.mycat.bigmem.chunkpageallot.bufferpage.BufferPageMoveInf;

/**
 * 缓冲内存页的数据
* 源文件名：BufferPage.java
* 文件版本：1.0.0
* 创建作者：liujun
* 创建日期：2016年12月27日
* 修改作者：liujun
* 修改日期：2016年12月27日
* 文件描述：TODO
* 版权所有：Copyright 2016 zjhz, Inc. All Rights Reserved.
*/
public class DirectMoveBufferPage extends BufferPageBase implements BufferPageMoveInf {

    /**
     * 进行分配的数组的初始化
     */
    private final Set<MycatBufferBase> allotBuffer;


    /**
     * 构造方法
     * @param buffer 参数
     * @param chunkSize 参数
     */
    public DirectMoveBufferPage(MycatBufferBase buffer, int chunkSize) {
        // 进行父类的引用
        super(buffer, chunkSize);

        // 进行分配对象记录容器的初始化
        this.allotBuffer = new TreeSet<>((cmp1, cmp2) -> {
            if (cmp1.address() > cmp2.address()) {
                return 1;
            } else if (cmp1.address() < cmp2.address()) {
                return -1;
            }

            return 0;
        });
    }

    /**
    * 检查当前内存页能否满足内存数据的分配要求
    * 方法描述
    * @param chunkNum 参数
    * @return 1,可分配 0，不能
    *  2016年12月19日
    */
    public boolean checkNeedChunk(int chunkNum) {
        // 仅在未锁定的情况下，才进行检查
        if (!isLock.get()) {
            // 如果当前可分配的内存块满足要求
            if (this.canUseChunkNum >= chunkNum) {
                return true;
            }
        }
        return false;
    }

    /* (non-Javadoc)
     * @see io.mycat.bigmem.chunkpageallot.bufferpage.BufferPageBase#alloactionMemory(int)
     */
    public MycatBufferBase alloactionMemory(int needChunkSize) {
        // 如果当前的可分配的内在块小于需要内存块，则返回
        if (canUseChunkNum < needChunkSize) {
            return null;
        }

        // 如果当前不能加锁成功，则返回为null
        if (!isLock.compareAndSet(false, true)) {
            return null;
        }

        try {
            int startIndex = -1;
            int endIndex = 0;
            // 找到当前连续未使用的内存块
            for (int i = 0; i < chunkCount; i++) {
                if (memUseSet.get(i) == false) {
                    if (startIndex == -1) {
                        startIndex = i;
                        endIndex = 1;
                        // 只需要1时，进不需再进行遍历
                        if (needChunkSize == 1) {
                            break;
                        }
                    } else {
                        if (++endIndex == needChunkSize) {
                            break;
                        }
                    }
                }
                // 如果已经使用，则标识当前已经使用，则从已经使用的位置开始
                else {
                    startIndex = -1;
                    endIndex = 0;
                }
            }

            // 如果找到适合的内存块大小
            if (endIndex == needChunkSize) {
                // 将这一批数据标识为已经使用
                int needChunkEnd = startIndex + needChunkSize;
                memUseSet.set(startIndex, needChunkEnd);

                // 标识为不可移动
                buffer.beginOp();

                // 标识开始与结束号
                buffer.putPosition(startIndex * chunkSize);
                buffer.limit(needChunkEnd * chunkSize);

                // 进行数据进行匹配分段操作
                MycatBufferBase bufferResult = buffer.slice();

                // 当前可使用的，为之前的结果前去当前的需要的，
                canUseChunkNum = canUseChunkNum - needChunkSize;

                // 将当前分配的对象信息记录到集合中
                this.allotBuffer.add(bufferResult);

                return bufferResult;
            }

        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } finally {
            // 标识当前操作完成
            buffer.commitOp();
            isLock.set(false);
        }

        return null;
    }

    /* (non-Javadoc)
     * @see io.mycat.bigmem.chunkpageallot.bufferpage.BufferPageBase#recycleBuffer(io.mycat.bigmem.chunkpageallot.buffer.MycatBufferBase, int, int)
     */
    public boolean recycleBuffer(MycatBufferBase bufferParam, int chunkStart, int chunkNum) {

        if (this.buffer == bufferParam) {
            // 如果加锁失败，则执行其他代码
            if (!isLock.compareAndSet(false, true)) {
                Thread.yield();
            }
            if (chunkNum > 0) {
                try {
                    // 进行开始动作
                    bufferParam.beginOp();

                    int endChunkNum = chunkStart + chunkNum;

                    // 将当前指定的内存块归还
                    memUseSet.clear(chunkStart, endChunkNum);

                    // 引用对象的容量进行重新标识
                    bufferParam.capacity(bufferParam.limit());
                    // 归还了内存，则需要将可使用的内存加上归还的内存
                    this.canUseChunkNum = canUseChunkNum + chunkNum;

                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    // 提交内存操作
                    bufferParam.commitOp();
                    isLock.set(false);
                }
            }

            return true;

        }

        return false;
    }

    @Override
    public Set<MycatBufferBase> getSliceMemory() {
        return allotBuffer;
    }

    @Override
    public void memoryCopy(MycatBufferBase useBuffer, int notUseIndex) {
        // 获得内存buffer
        DirectMemAddressInf thisNavBuf = (DirectMemAddressInf) useBuffer;
        // attachment对象在buf.slice();的时候将attachment对象设置为总的buff对象
        DirectMemAddressInf parentBuf = (DirectMemAddressInf) thisNavBuf.getAttach();

        if (this.buffer == parentBuf) {

            // 检查当前对象是否实现了可移动接口
            if (buffer instanceof MycatMovableBufer) {
                // 进行内存的拷贝操作
                try {
                    this.buffer.beginOp();

                    MycatMovableBufer moveBuffer = (MycatMovableBufer) buffer;

                    long addressBase = buffer.address();

                    int startChunk = (int) ((useBuffer.address() - addressBase) / chunkSize);

                    // 清除已经使用的内存块
                    clearBitSet(startChunk, (startChunk + useBuffer.limit() / chunkSize));

                    long tarAddress = addressBase + notUseIndex * chunkSize;
                    // 进行内存的拷贝操作
                    moveBuffer.memoryCopy(useBuffer.address(), tarAddress, useBuffer.limit());

                    // 引用地址重置
                    useBuffer.address(tarAddress);

                    // 重新标识已经使用的块
                    setBitSet(notUseIndex, notUseIndex + useBuffer.limit() / chunkSize);

                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    this.buffer.commitOp();
                }
            }

        }
    }

    private void clearBitSet(int startChunk, int endChunk) {
        for (int i = startChunk; i < endChunk; i++) {
            this.memUseSet.clear(i);
        }
    }

    private void setBitSet(int startChunk, int endChunk) {
        for (int i = startChunk; i < endChunk; i++) {
            this.memUseSet.set(i, true);
        }
    }

}
