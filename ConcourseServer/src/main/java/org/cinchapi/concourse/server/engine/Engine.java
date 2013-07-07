/*
 * The MIT License (MIT)
 * 
 * Copyright (c) 2013 Jeff Nelson, Cinchapi Software Collective
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.cinchapi.concourse.server.engine;

import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.annotation.concurrent.ThreadSafe;

import org.cinchapi.common.annotate.DoNotInvoke;
import org.cinchapi.common.multithread.Lock;
import org.cinchapi.concourse.thrift.TObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.*;

/**
 * The {@code Engine} schedules concurrent CRUD operations, manages ACID
 * transactions, versions writes and indexes data.
 * <p>
 * The Engine is a {@link BufferingService}. Writing to the {@link Database} is
 * expensive because multiple index records must be deserialized, updated and
 * flushed back to disk for each revision. By using a {@link Buffer}, the Engine
 * can handles writes in a more efficient manner which minimal impact on Read
 * performance. The buffering system provides full CD guarantees.
 * </p>
 * 
 * @author jnelson
 */
@ThreadSafe
public final class Engine extends BufferingService implements
		Transactional,
		Destination {

	private static final Logger log = LoggerFactory.getLogger(Engine.class);

	/**
	 * Construct a new Engine instance.
	 */
	public Engine() {
		super(new Buffer(), new Database());
	}

	/**
	 * Construct a new instance.
	 * 
	 * @param buffer
	 * @param database
	 */
	private Engine(Buffer buffer, Database database) {
		super(buffer, database);
	}

	/*
	 * (non-Javadoc)
	 * The Engine is a Destination for Transaction commits. The accept method
	 * here will accept a write from a Transaction and create a new Write
	 * within the underlying BufferingService (i.e. it will create a Write in
	 * the buffer that will eventually be flushed to the database). Creating a
	 * new Write does associate a new timestamp with the data, but this is the
	 * desired behaviour because the timestamp associated with transactional
	 * data should be the timestamp of the data post commit.
	 */
	@Override
	@DoNotInvoke
	public void accept(Write write) {
		checkArgument(write.getType() != WriteType.NOT_FOR_STORAGE);
		Lock lock = writeLock();
		try {
			String key = write.getKey().toString();
			TObject value = write.getValue().getQuantity();
			long record = write.getRecord().longValue();
			boolean accepted = write.getType() == WriteType.ADD ? add(key,
					value, record) : remove(key, value, record);
			if(!accepted) {
				log.warn("Write {} was rejected by the Engine"
						+ "because it was previously accepted "
						+ "but not offset. This implies that a "
						+ "premature shutdown occured and the parent"
						+ "Transaction is attempting to restore"
						+ "itself from backup and finish committing.", write);
			}
			else {
				log.debug("'{}' was accepted by the Engine", write);
			}
		}
		finally {
			lock.release();
		}

	}

	@Override
	public TransactionLock lockAndIsolate(String key, long record) {
		return TransactionLock.lockAndIsolate(key, record);
	}

	@Override
	public TransactionLock lockAndShare(long record) {
		return TransactionLock.lockandShare(record);
	}

	@Override
	public TransactionLock lockAndShare(String key) {
		return TransactionLock.lockAndShare(key);
	}

	@Override
	public TransactionLock lockAndShare(String key, long record) {
		return TransactionLock.lockAndShare(key, record);
	}

	/**
	 * Shutdown the database gracefully. Make sure any blocked tasks
	 * have a chance to complete before being dropped.
	 */
	public void shutdown() {
		((Database) this.destination).shutdown();
		executor.shutdown();
		try {
			if(!executor.awaitTermination(60, TimeUnit.SECONDS)) {
				List<Runnable> tasks = executor.shutdownNow();
				log.error("The Engine could not properly shutdown. "
						+ "The following tasks were dropped: {}", tasks);
			}
		}
		catch (InterruptedException e) {
			log.error("An error occured while shutting down the Engine: {}", e);
		}
		log.info("The Engine has shutdown");
	}

	@Override
	public Transaction startTransaction() {
		return Transaction.start(this);
	}

}
